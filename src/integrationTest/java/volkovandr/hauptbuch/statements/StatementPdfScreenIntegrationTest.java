package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): the PDF way into a statement (slice e1) driven through MockMvc
 * against real Postgres — a PDF profile is saved, a PDF is uploaded, the account is proposed from
 * the unmasked text, the statement is created {@code new} with the masked text, the text is edited,
 * and a scan without a text layer is refused. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hauptbuch.statements.storage-root=build/tmp/statements-it")
@Transactional
class StatementPdfScreenIntegrationTest {

  private static final String IBAN = "XX00 1111 2222";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  private long accountId;
  private long profileId;

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
    accountService.updateDetection(accountId, IBAN, false);
    mockMvc
        .perform(
            post("/statements/profiles/save")
                .param("name", "BankBbb PDF")
                .param("format", "pdf")
                .param("aiNote", "  Description carries the rate ")
                .param("windowDaysBefore", "10")
                .param("windowDaysAfter", "3"))
        .andExpect(redirectedUrl("/statements/profiles"));
    profileId =
        jdbcClient
            .sql("select statement_profile_id from statement_profile where name = 'BankBbb PDF'")
            .query(Long.class)
            .single();
  }

  @Test
  void pdfProfileIsSavedWithOnlyItsNameWindowAndNote() {
    Map<String, Object> row =
        jdbcClient
            .sql("select * from statement_profile where statement_profile_id = :id")
            .param("id", profileId)
            .query()
            .singleRow();

    assertThat(row.get("format")).isEqualTo("pdf");
    assertThat(row.get("ai_note")).isEqualTo("Description carries the rate");
    assertThat(row.get("csv_delimiter")).isNull();
    assertThat(row.get("col_booking_date")).isNull();
  }

  @Test
  void pdfProfileEditorHasNoColumnMapAndLink() throws Exception {
    mockMvc
        .perform(get("/statements/profiles/new-pdf"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("AI note")))
        .andExpect(content().string(not(containsString("Booking date column"))));
    mockMvc
        .perform(get("/statements/profiles"))
        .andExpect(content().string(containsString("New PDF profile")));
  }

  @Test
  void uploadedPdfBecomesNewStatementWithItsMaskedText() throws Exception {
    String confirmUrl =
        StatementPdfFixtures.upload(
            mockMvc,
            profileId,
            StatementPdfFixtures.pdf(
                "Account " + IBAN, "BIC: ABCDXXYY", "02.05.2026 ShopAaa -12.50"));

    MvcResult confirm =
        mockMvc
            .perform(get(URI.create(confirmUrl)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("text layer")))
            .andExpect(content().string(containsString("Proposed from the account number")))
            .andReturn();
    assertThat(confirm.getResponse().getContentAsString())
        .containsPattern("value=\"" + accountId + "\"[^>]*selected");

    MvcResult created =
        mockMvc
            .perform(
                post("/statements/create")
                    .param("profileId", String.valueOf(profileId))
                    .param("path", StatementFixtures.pathOf(confirmUrl))
                    .param("name", "2026-05.pdf")
                    .param("account", String.valueOf(accountId)))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    String redirect = Objects.requireNonNull(created.getResponse().getRedirectedUrl());
    long statementId = Long.parseLong(redirect.substring(redirect.lastIndexOf('/') + 1));

    Map<String, Object> row =
        jdbcClient
            .sql("select * from statement where statement_id = :id")
            .param("id", statementId)
            .query()
            .singleRow();
    assertThat(row.get("state")).isEqualTo("new");
    assertThat(row.get("account_id")).isEqualTo(accountId);
    assertThat((String) row.get("sent_text"))
        .contains("[ACCOUNT]", "BIC: [BIC]", "ShopAaa")
        .doesNotContain("1111");
    assertThat(
            jdbcClient
                .sql("select count(*) from statement_line where statement_id = :id")
                .param("id", statementId)
                .query(Long.class)
                .single())
        .isZero();

    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("PDF text")))
        .andExpect(content().string(containsString("[ACCOUNT]")));

    mockMvc
        .perform(post("/statements/" + statementId + "/text").param("text", "only the table"))
        .andExpect(redirectedUrl("/statements/" + statementId))
        .andExpect(flash().attribute("notice", "Text saved."));
    assertThat(
            jdbcClient
                .sql("select sent_text from statement where statement_id = :id")
                .param("id", statementId)
                .query(String.class)
                .single())
        .isEqualTo("only the table");
  }

  @Test
  void scanWithoutTextLayerIsRefusedAtTheConfirmStep() throws Exception {
    String confirmUrl = StatementPdfFixtures.upload(mockMvc, profileId, StatementPdfFixtures.pdf());

    mockMvc
        .perform(get(URI.create(confirmUrl)))
        .andExpect(redirectedUrl("/statements"))
        .andExpect(flash().attribute("error", containsString("no text layer")));
  }

  @Test
  void textOfCsvStatementCannotBeEdited() throws Exception {
    long csvProfile = StatementFixtures.saveProfile(mockMvc, jdbcClient);
    long statementId = StatementFixtures.uploadAndCreate(mockMvc, csvProfile, accountId);

    mockMvc
        .perform(post("/statements/" + statementId + "/text").param("text", "x"))
        .andExpect(flash().attribute("error", containsString("can no longer be edited")));
  }
}
