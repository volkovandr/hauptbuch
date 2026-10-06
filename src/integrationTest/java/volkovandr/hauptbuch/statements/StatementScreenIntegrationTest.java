package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): the statement screens of slices b1–b3 driven through MockMvc
 * against real Postgres — a profile is created and previews a sample, a CSV is uploaded, confirmed
 * onto the account its IBAN points at, then corrected and deleted. Each test is rolled back; the
 * uploaded files land in a throwaway directory.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hauptbuch.statements.storage-root=build/tmp/statements-it")
@Transactional
class StatementScreenIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  private long accountId;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency("EUR");
    accountId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankAaa-EUR",
                    "asset",
                    null,
                    "EUR",
                    LocalDate.parse("2026-01-01"),
                    BigDecimal.ZERO))
            .accountId();
    accountService.updateDetection(accountId, "XX00 1111 2222", false);
  }

  private long saveProfile() throws Exception {
    return StatementFixtures.saveProfile(mockMvc, jdbcClient);
  }

  private long uploadAndCreate(long profileId) throws Exception {
    return StatementFixtures.uploadAndCreate(mockMvc, profileId, accountId);
  }

  @Test
  void uploadedCsvBecomesStatementWithItsLinesAndTheProposedAccount() throws Exception {
    long statementId = uploadAndCreate(saveProfile());

    List<Map<String, Object>> lines =
        jdbcClient
            .sql("select * from statement_line where statement_id = :id order by sort_order")
            .param("id", statementId)
            .query()
            .listOfRows();
    assertThat(lines).hasSize(4);
    assertThat(lines.get(2).get("problem")).isEqualTo("Currency USD, but the account is in EUR");
    assertThat(lines.get(3).get("problem")).isEqualTo("Unreadable booking date 'soon'");
    Map<String, Object> statement =
        jdbcClient
            .sql("select * from statement where statement_id = :id")
            .param("id", statementId)
            .query()
            .singleRow();
    assertThat(statement.get("account_id")).isEqualTo(accountId);
    assertThat(statement.get("period_start").toString()).isEqualTo("2026-05-02");
    assertThat(statement.get("period_end").toString()).isEqualTo("2026-05-07");

    mockMvc
        .perform(get("/statements"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("BankAaa-EUR")))
        .andExpect(content().string(containsString("02.05.2026")))
        .andExpect(content().string(containsString("2026-05.csv")));
    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ShopAaa")))
        .andExpect(content().string(containsString("-12,50")))
        .andExpect(content().string(containsString("1.234,56")))
        .andExpect(content().string(containsString("Unreadable booking date")));
  }

  @Test
  void theConfirmStepProposesTheAccountWhoseLabelIsTheTailOfTheFileIban() throws Exception {
    accountService.updateDetection(accountId, "2222", false);
    long profileId = saveProfile();

    String confirm =
        mockMvc
            .perform(get(URI.create(StatementFixtures.upload(mockMvc, profileId))))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Proposed from the file")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(confirm).containsPattern("value=\"" + accountId + "\"[^>]*selected");
  }

  @Test
  void theAccountPickedOnTheUploadFormIsPreselectedOnTheConfirmStep() throws Exception {
    long otherId =
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
    long profileId = saveProfile();

    String confirmUrl = StatementFixtures.upload(mockMvc, profileId, otherId);

    assertThat(confirmUrl).contains("account=" + otherId);
    String confirm =
        mockMvc
            .perform(get(URI.create(confirmUrl)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(confirm).containsPattern("value=\"" + otherId + "\"[^>]*selected");
    assertThat(confirm).doesNotContainPattern("value=\"" + accountId + "\"[^>]*selected");
    assertThat(confirm).doesNotContain("Proposed from the file");
  }

  @Test
  void theConfirmStepRefusesToCreateWithoutAccount() throws Exception {
    long profileId = saveProfile();
    String path = StatementFixtures.pathOf(StatementFixtures.upload(mockMvc, profileId));

    mockMvc
        .perform(
            post("/statements/create")
                .param("profileId", String.valueOf(profileId))
                .param("path", path)
                .param("name", "x.csv"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("error", "Choose the account this statement is for."));
    assertThat(jdbcClient.sql("select count(*) from statement").query(Long.class).single())
        .isZero();
  }

  @Test
  void emptyUploadIsRefusedOnTheStatementsPage() throws Exception {
    long profileId = saveProfile();

    mockMvc
        .perform(
            multipart("/statements/upload")
                .file(new MockMultipartFile("file", "x.csv", "text/csv", new byte[0]))
                .param("profile", String.valueOf(profileId)))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/statements"))
        .andExpect(flash().attribute("error", "No file was attached — pick a file and try again."));
  }

  @Test
  void theHeaderAndTheLinesCanBeCorrected() throws Exception {
    long statementId = uploadAndCreate(saveProfile());
    List<Long> ids =
        jdbcClient
            .sql(
                "select statement_line_id from statement_line where statement_id = :id"
                    + " order by sort_order")
            .param("id", statementId)
            .query(Long.class)
            .list();

    mockMvc
        .perform(
            post("/statements/" + statementId + "/header")
                .param("periodStart", "2026-05-01")
                .param("periodEnd", "2026-05-31")
                .param("openingBalance", "100,00")
                .param("closingBalance", "1.321,06"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("notice", "Header saved."));
    Map<String, Object> header =
        jdbcClient
            .sql("select * from statement where statement_id = :id")
            .param("id", statementId)
            .query()
            .singleRow();
    assertThat(header.get("period_start").toString()).isEqualTo("2026-05-01");
    assertThat((BigDecimal) header.get("closing_balance")).isEqualByComparingTo("1321.06");

    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines")
                .param("line", String.valueOf(ids.get(3)))
                .param("bookingDate_" + ids.get(3), "2026-05-08")
                .param("valueDate_" + ids.get(3), "")
                .param("amount_" + ids.get(3), "-1,00")
                .param("counterparty_" + ids.get(3), "ShopCcc")
                .param("description_" + ids.get(3), "Fixed")
                .param("bankCategory_" + ids.get(3), ""))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("notice", "Lines saved."));
    Map<String, Object> fixed =
        jdbcClient
            .sql("select * from statement_line where statement_line_id = :id")
            .param("id", ids.get(3))
            .query()
            .singleRow();
    assertThat(fixed.get("problem")).isNull();
    assertThat(fixed.get("description")).isEqualTo("Fixed");
    assertThat(fixed.get("booking_date").toString()).isEqualTo("2026-05-08");
  }

  @Test
  void badValueInTheGridIsReportedAndSavesNothing() throws Exception {
    long statementId = uploadAndCreate(saveProfile());
    long first =
        jdbcClient
            .sql(
                "select statement_line_id from statement_line where statement_id = :id"
                    + " order by sort_order limit 1")
            .param("id", statementId)
            .query(Long.class)
            .single();

    mockMvc
        .perform(
            post("/statements/" + statementId + "/lines")
                .param("line", String.valueOf(first))
                .param("bookingDate_" + first, "2026-05-08")
                .param("amount_" + first, "lots"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("error", "Line 1: The amount 'lots' is not a number."));
    assertThat(
            jdbcClient
                .sql("select booking_date from statement_line where statement_line_id = :id")
                .param("id", first)
                .query(java.sql.Date.class)
                .single()
                .toString())
        .isEqualTo("2026-05-02");
  }

  @Test
  void deletingStatementHidesItAndKeepsItsRows() throws Exception {
    long statementId = uploadAndCreate(saveProfile());

    mockMvc
        .perform(post("/statements/" + statementId + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/statements"));

    assertThat(
            jdbcClient
                .sql("select deleted_at is not null from statement where statement_id = :id")
                .param("id", statementId)
                .query(Boolean.class)
                .single())
        .isTrue();
    mockMvc
        .perform(get("/statements"))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("2026-05.csv"))));
  }

  @Test
  void theStatementsListFiltersByAccount() throws Exception {
    uploadAndCreate(saveProfile());
    long other =
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

    mockMvc
        .perform(get("/statements").param("account", String.valueOf(other)))
        .andExpect(content().string(containsString("No statements yet.")));
    mockMvc
        .perform(get("/statements").param("account", String.valueOf(accountId)))
        .andExpect(content().string(containsString("2026-05.csv")));
  }

  @Test
  void theNavigationShowsStatements() throws Exception {
    mockMvc
        .perform(get("/statements"))
        .andExpect(content().string(containsString("href=\"/statements\"")));
  }

  @Test
  void statementPageStillOpensAfterItsProfileIsDeleted() throws Exception {
    long profileId = saveProfile();
    long statementId = uploadAndCreate(profileId);
    mockMvc.perform(post("/statements/profiles/" + profileId + "/delete"));

    mockMvc
        .perform(get("/statements/" + statementId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("BankAaa CSV")));
  }

  @Test
  void missingStatementSendsYouBackToTheList() throws Exception {
    mockMvc
        .perform(get("/statements/999999"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/statements"))
        .andExpect(flash().attribute("error", "That statement no longer exists."));
  }
}
