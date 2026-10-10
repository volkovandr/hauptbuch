package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): the AI call of a PDF statement (slice e2) driven through MockMvc
 * against real Postgres with the {@link StatementParser} faked — a statement parses into its lines
 * and header with the usage and frozen cost stored; an undecodable body and a transport failure
 * land {@code failed} with the reason shown and the text still editable; the statement prompt
 * editor saves and resets. Each test is rolled back.
 */
// ExcessiveImports: an end-to-end screen test needs the MockMvc, settings and statement fixtures.
@SuppressWarnings("PMD.ExcessiveImports")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hauptbuch.statements.storage-root=build/tmp/statements-it")
@Transactional
class StatementParseScreenIntegrationTest {

  private static final String GOOD_BODY =
      """
      statement:
        periodStart: 2026-05-01
        periodEnd: 2026-05-31
        openingBalance: 1200.50
        closingBalance: 1138.00
      lines[2]{bookingDate,valueDate,amount,counterparty,description,bankCategory,\
      originalAmount,originalCurrency,originalRate}:
        2026-05-02,2026-05-02,-12.50,ShopAaa,Card payment,Groceries,,,
        2026-05-09,2026-05-10,-50.00,ShopBbb,Card 55.00 USD,Shopping,55.00,USD,1.10
      """;

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;
  @MockitoBean StatementParser parser;

  private long statementId;

  @BeforeEach
  void setUp() throws Exception {
    settingsService.setBaseCurrency("EUR");
    settingsService.setAiPrices(
        new BigDecimal("3"), new BigDecimal("15"), BigDecimal.ZERO, BigDecimal.ZERO);
    long accountId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankBbb-EUR",
                    "asset",
                    null,
                    "EUR",
                    LocalDate.parse("2026-01-01"),
                    BigDecimal.ZERO))
            .accountId();
    mockMvc.perform(
        post("/statements/profiles/save")
            .param("name", "BankBbb PDF")
            .param("format", "pdf")
            .param("windowDaysBefore", "10")
            .param("windowDaysAfter", "3"));
    long profileId =
        jdbcClient
            .sql("select statement_profile_id from statement_profile where name = 'BankBbb PDF'")
            .query(Long.class)
            .single();
    String confirmUrl =
        StatementPdfFixtures.upload(
            mockMvc, profileId, StatementPdfFixtures.pdf("02.05.2026 ShopAaa -12.50"));
    String redirect =
        Objects.requireNonNull(
            mockMvc
                .perform(
                    post("/statements/create")
                        .param("profileId", String.valueOf(profileId))
                        .param("path", StatementFixtures.pathOf(confirmUrl))
                        .param("name", "2026-05.pdf")
                        .param("account", String.valueOf(accountId)))
                .andReturn()
                .getResponse()
                .getRedirectedUrl());
    statementId = Long.parseLong(redirect.substring(redirect.lastIndexOf('/') + 1));
  }

  @Test
  void parseSeedsTheLinesAndHeaderAndStoresUsageAndCost() throws Exception {
    when(parser.parse(any())).thenReturn(new StatementParseResult(GOOD_BODY, 1000, 200, 0, 0));

    mockMvc
        .perform(post("/statements/" + statementId + "/parse"))
        .andExpect(redirectedUrl("/statements/" + statementId))
        .andExpect(flash().attributeExists("notice"));

    Map<String, Object> row = statementRow();
    assertThat(row.get("state")).isEqualTo("processed");
    assertThat(row.get("period_start")).hasToString("2026-05-01");
    assertThat((BigDecimal) row.get("closing_balance")).isEqualByComparingTo("1138.00");
    assertThat(row.get("parse_raw")).isEqualTo(GOOD_BODY);
    assertThat((BigDecimal) row.get("parse_cost")).isEqualByComparingTo("0.006");
    List<Map<String, Object>> lines =
        jdbcClient
            .sql("select * from statement_line where statement_id = :id order by sort_order")
            .param("id", statementId)
            .query()
            .listOfRows();
    assertThat(lines).hasSize(2);
    assertThat(lines.get(1).get("original_currency_code")).isEqualTo("USD");
    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ShopBbb")));
  }

  @Test
  void reverseSignsFlipsEveryParsedLine() throws Exception {
    when(parser.parse(any())).thenReturn(new StatementParseResult(GOOD_BODY, 1, 1, 0, 0));
    mockMvc.perform(post("/statements/" + statementId + "/parse"));

    mockMvc
        .perform(post("/statements/" + statementId + "/reverse-signs"))
        .andExpect(redirectedUrl("/statements/" + statementId))
        .andExpect(flash().attribute("notice", "The signs were reversed."));

    List<BigDecimal> amounts =
        jdbcClient
            .sql("select amount from statement_line where statement_id = :id order by sort_order")
            .param("id", statementId)
            .query(BigDecimal.class)
            .list();
    assertThat(amounts.get(0)).isEqualByComparingTo("12.50");
    assertThat(amounts.get(1)).isEqualByComparingTo("50.00");
  }

  @Test
  void anUndecodableBodyFailsKeepingTheRawResponse() throws Exception {
    when(parser.parse(any()))
        .thenReturn(new StatementParseResult("lines[2]{a,b}:\n  one\n", 10, 5, 0, 0));

    mockMvc
        .perform(post("/statements/" + statementId + "/parse"))
        .andExpect(flash().attribute("error", containsString("parse failed")));

    assertThat(statementRow().get("state")).isEqualTo("failed");
    assertThat(statementRow().get("parse_raw")).isEqualTo("lines[2]{a,b}:\n  one\n");
    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(content().string(containsString("Could not decode the parser response")))
        .andExpect(content().string(containsString("Parse with AI")));
  }

  @Test
  void transportFailureFailsWithTheReasonAndCanBeRetried() throws Exception {
    when(parser.parse(any()))
        .thenThrow(new StatementParseException("No Anthropic API key configured"));
    mockMvc.perform(post("/statements/" + statementId + "/parse"));
    assertThat(statementRow().get("state")).isEqualTo("failed");
    assertThat(statementRow().get("parse_error")).isEqualTo("No Anthropic API key configured");

    doReturn(new StatementParseResult(GOOD_BODY, 1, 1, 0, 0)).when(parser).parse(any());
    mockMvc.perform(post("/statements/" + statementId + "/parse"));

    assertThat(statementRow().get("state")).isEqualTo("processed");
    assertThat(statementRow().get("parse_error")).isNull();
  }

  @Test
  void processedStatementCannotBeParsedAgain() throws Exception {
    when(parser.parse(any())).thenReturn(new StatementParseResult(GOOD_BODY, 1, 1, 0, 0));
    mockMvc.perform(post("/statements/" + statementId + "/parse"));

    mockMvc
        .perform(post("/statements/" + statementId + "/parse"))
        .andExpect(flash().attribute("error", containsString("not waiting to be parsed")));
  }

  @Test
  void theStatementPromptEditorSavesAndResets() throws Exception {
    mockMvc
        .perform(get("/statements/ai-prompt"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("built-in default is in effect")));

    mockMvc
        .perform(post("/statements/ai-prompt").param("instructions", "Only read the table."))
        .andExpect(content().string(containsString("A custom prompt is in effect")));
    assertThat(settingsService.statementSystemPrompt()).isEqualTo("Only read the table.");

    mockMvc.perform(post("/statements/ai-prompt").param("reset", "true"));
    assertThat(settingsService.statementSystemPrompt()).isNull();
  }

  private Map<String, Object> statementRow() {
    return jdbcClient
        .sql("select * from statement where statement_id = :id")
        .param("id", statementId)
        .query()
        .singleRow();
  }
}
