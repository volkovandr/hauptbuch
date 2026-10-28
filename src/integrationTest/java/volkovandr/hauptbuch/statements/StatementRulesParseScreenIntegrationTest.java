package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
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
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.ledger.TransactionDraft;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;
import volkovandr.hauptbuch.statements.repository.StatementProfileRuleRepository;

/**
 * Integration tier (CLAUDE.md §6): Parse on a statement uploaded through a PDF (rules) profile
 * (statements.md §3.2a) driven through MockMvc against real Postgres — the text is kept unmasked,
 * Parse seeds the lines and header with no AI call and no usage, a re-parse after a rule change
 * replaces them, and a re-parse is refused while a line is matched. Each test is rolled back.
 */
// ExcessiveImports: an end-to-end screen test needs the MockMvc, ledger and statement fixtures.
@SuppressWarnings("PMD.ExcessiveImports")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hauptbuch.statements.storage-root=build/tmp/statements-it")
@Transactional
class StatementRulesParseScreenIntegrationTest {

  private static final String IBAN_LINE = "IBAN DE89370400440532013000";
  private static final String OPENING = "^Opening (?<openingBalance>[\\d.,]+)$";
  private static final String CLOSING = "^Closing (?<closingBalance>[\\d.,]+)$";
  private static final String BOOKING =
      "^(?<bookingDate>\\d\\d\\.\\d\\d\\.\\d{4}) (?<counterparty>\\S+) (?<amount>[\\d.,]+-?)$";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired LedgerService ledgerService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;
  @Autowired StatementProfileRuleRepository ruleRepository;
  @Autowired StatementMatchRepository matchRepository;
  @MockitoBean StatementParser parser;

  private long accountId;
  private long profileId;
  private long statementId;

  @BeforeEach
  void setUp() throws Exception {
    settingsService.setBaseCurrency("EUR");
    accountId =
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
            .param("name", "BankBbb rules")
            .param("format", "pdf_rules")
            .param("windowDaysBefore", "10")
            .param("windowDaysAfter", "3")
            .param("rulesThousandsSeparator", ".")
            .param("rulesDecimalSeparator", ",")
            .param("rulesMinusPosition", "trailing")
            .param("rulesDateFormat", "dd.MM.yyyy")
            .param("rulesContinuation", "previous")
            .param("ruleKind", "header", "header", "booking")
            .param("rulePattern", OPENING, CLOSING, BOOKING)
            .param("rulePatternEnd", "", "", ""));
    profileId =
        jdbcClient
            .sql("select statement_profile_id from statement_profile where name = 'BankBbb rules'")
            .query(Long.class)
            .single();
    String confirmUrl =
        StatementPdfFixtures.upload(
            mockMvc,
            profileId,
            StatementPdfFixtures.pdf(
                IBAN_LINE,
                "Opening 1.200,50",
                "02.05.2026 ShopAaa 12,50-",
                "09.05.2026 ShopBbb 50,00-",
                "Closing 1.138,00"));
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

  private Map<String, Object> statementRow() {
    return jdbcClient
        .sql("select * from statement where statement_id = :id")
        .param("id", statementId)
        .query()
        .singleRow();
  }

  private List<Map<String, Object>> lines() {
    return jdbcClient
        .sql("select * from statement_line where statement_id = :id order by sort_order")
        .param("id", statementId)
        .query()
        .listOfRows();
  }

  @Test
  void theUploadedTextIsKeptUnmasked() {
    assertThat((String) statementRow().get("sent_text")).contains(IBAN_LINE);
  }

  @Test
  void parseSeedsTheLinesAndHeaderWithoutAnAiCallOrUsage() throws Exception {
    mockMvc
        .perform(post("/statements/" + statementId + "/parse"))
        .andExpect(redirectedUrl("/statements/" + statementId))
        .andExpect(flash().attributeExists("notice"));

    Map<String, Object> row = statementRow();
    assertThat(row.get("state")).isEqualTo("processed");
    assertThat((BigDecimal) row.get("opening_balance")).isEqualByComparingTo("1200.50");
    assertThat((BigDecimal) row.get("closing_balance")).isEqualByComparingTo("1138.00");
    assertThat((String) row.get("parse_raw")).contains("ShopAaa");
    assertThat(row.get("tokens_in")).isNull();
    assertThat(row.get("parse_cost")).isNull();
    assertThat(lines()).hasSize(2);
    assertThat((BigDecimal) lines().get(1).get("amount")).isEqualByComparingTo("-50.00");
    verifyNoInteractions(parser);
    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ShopBbb")))
        .andExpect(content().string(containsString("Parse with rules")))
        .andExpect(content().string(not(containsString("cache write"))));
  }

  @Test
  void parseAgainAfterRuleChangeReplacesTheLinesAndHeader() throws Exception {
    mockMvc.perform(post("/statements/" + statementId + "/parse"));
    ruleRepository.replace(
        profileId,
        List.of(
            new ParseRule(ParseRule.HEADER, CLOSING, null),
            new ParseRule(
                ParseRule.BOOKING,
                "^(?<bookingDate>09\\.05\\.2026) (?<counterparty>\\S+) (?<amount>[\\d.,]+-?)$",
                null)));

    mockMvc
        .perform(post("/statements/" + statementId + "/parse"))
        .andExpect(flash().attributeExists("notice"));

    assertThat(lines()).hasSize(1);
    assertThat(lines().get(0).get("counterparty")).isEqualTo("ShopBbb");
    assertThat(statementRow().get("opening_balance")).isNull();
  }

  @Test
  void processedStatementTextCanBeFixedAndParsedAgain() throws Exception {
    mockMvc.perform(post("/statements/" + statementId + "/parse"));

    mockMvc
        .perform(
            post("/statements/" + statementId + "/text")
                .param("text", "Opening 1.200,50\n02.05.2026 ShopAaa 12,50-\n"))
        .andExpect(flash().attribute("notice", "Text saved."));
    mockMvc.perform(post("/statements/" + statementId + "/parse"));

    assertThat(lines()).hasSize(1);
  }

  @Test
  void textWithNoBookingFailsWithThatReason() throws Exception {
    ruleRepository.replace(
        profileId, List.of(new ParseRule(ParseRule.BOOKING, "^NEVER (?<amount>\\d+)$", null)));

    mockMvc
        .perform(post("/statements/" + statementId + "/parse"))
        .andExpect(flash().attributeExists("error"));

    assertThat(statementRow().get("state")).isEqualTo("failed");
    assertThat((String) statementRow().get("parse_error")).contains("No booking found");
  }

  @Test
  void parseAgainIsRefusedWhileLineIsMatched() throws Exception {
    mockMvc.perform(post("/statements/" + statementId + "/parse"));
    long transactionId =
        ledgerService.recordTransaction(
            new TransactionDraft(
                LocalDate.parse("2026-05-02"),
                null,
                null,
                "confirmed",
                List.of(
                    PostingDraft.of(accountId, new BigDecimal("-12.50")),
                    PostingDraft.of(
                        accountService.insertLeaf("Food", "expense", null, "EUR").accountId(),
                        new BigDecimal("12.50")))));
    long postingId =
        jdbcClient
            .sql("select posting_id from posting where transaction_id = :t and account_id = :a")
            .param("t", transactionId)
            .param("a", accountId)
            .query(Long.class)
            .single();
    long lineId =
        jdbcClient
            .sql(
                "select statement_line_id from statement_line where statement_id = :s"
                    + " and amount = -12.50")
            .param("s", statementId)
            .query(Long.class)
            .single();
    matchRepository.insertMatch(statementId, lineId, postingId);

    mockMvc
        .perform(post("/statements/" + statementId + "/parse"))
        .andExpect(flash().attribute("error", containsString("Unmatch")));

    assertThat(lines()).hasSize(2);
  }
}
