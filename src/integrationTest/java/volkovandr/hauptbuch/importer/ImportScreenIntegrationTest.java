package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (import.md §12): the {@code /import} screen driven through its controller —
 * upload → preview → override, and the same-name replacement-or-coincidence prompt. Asserts the b2
 * contract that <strong>nothing is written to any staging table</strong>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportScreenIntegrationTest {

  private static final String DAY_MONTH_BANK =
      """
      !Type:Bank
      D01/07'2004
      T-12.34
      PGrocer
      LFood
      ^
      D28/07'2004
      T-5.00
      PBaker
      LFood
      ^
      """;

  /** Opens with Money's opening-balance self-transfer, which names the account (import.md §5.1). */
  private static final String BANK_WITH_OPENING_BALANCE =
      """
      !Type:Bank
      D01/07'2004
      T0.00
      CX
      POpening Balance
      L[Bank24ru-EUR]
      ^
      D28/07'2004
      T-5.00
      PBaker
      LFood
      ^
      """;

  private static final String INVESTMENT = "!Type:Invst\nD01/07'2004\n^\n";

  /** A second account whose file both transfers to "Current Account" and reuses the "Food" path. */
  private static final String SAVINGS_WITH_TRANSFER =
      """
      !Type:Bank
      D22/07'2004
      T-20.00
      PMovers
      L[Current Account]
      ^
      D23/07'2004
      T-8.00
      PBaker
      LFood
      ^
      """;

  /** Both components ≤ 12 in every date — the order cannot be inferred (import.md §4.3). */
  private static final String AMBIGUOUS_CARD =
      "!Type:CCard\nD01/02'2005\nT-9.99\nPShop\nLStuff\n^\n";

  /** Transfers to a counterparty that is really money lent to a person (import.md §5.4). */
  private static final String BANK_WITH_PERSON_TRANSFER =
      """
      !Type:Bank
      D22/07'2004
      T-20.00
      PMax
      L[Loan to Max]
      ^
      """;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;

  private static MockMultipartFile qif(String name, String text) {
    return new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8));
  }

  private MockHttpSession openCampaign() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc.perform(post("/import/session").session(session)).andExpect(redirectedUrl("/import"));
    return session;
  }

  /**
   * Upload, then state the Money account on the preview the way the owner does (import.md §4.1).
   */
  private String upload(MockHttpSession session, MockMultipartFile file, String account)
      throws Exception {
    String token = uploadOnly(session, file);
    mockMvc
        .perform(
            post("/import/uploads/" + token).param("moneyAccountName", account).session(session))
        .andExpect(redirectedUrl("/import/uploads/" + token));
    return token;
  }

  private String uploadOnly(MockHttpSession session, MockMultipartFile file) throws Exception {
    MvcResult result =
        mockMvc
            .perform(multipart("/import/uploads").file(file).session(session))
            .andExpect(redirectedUrlPattern("/import/uploads/*"))
            .andReturn();
    String location =
        Objects.requireNonNull(result.getResponse().getRedirectedUrl(), "no redirect Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private void stage(MockHttpSession session, String token) throws Exception {
    mockMvc
        .perform(post("/import/uploads/" + token + "/stage").session(session))
        .andExpect(redirectedUrl("/import"));
  }

  private String stageNewFile(MockHttpSession session, String name, String text, String account)
      throws Exception {
    String token = upload(session, qif(name, text), account);
    stage(session, token);
    return token;
  }

  private long insertAccount(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:name, :type, 'EUR')"
                + " returning account_id")
        .param("name", name)
        .param("type", type)
        .query(Long.class)
        .single();
  }

  private long mapRowId(String moneyAccountName) {
    return jdbcClient
        .sql("select import_account_id from import_account where money_account_name = :name")
        .param("name", moneyAccountName)
        .query(Long.class)
        .single();
  }

  private Long mappedAccountId(String moneyAccountName) {
    return jdbcClient
        .sql("select account_id from import_account where money_account_name = :name")
        .param("name", moneyAccountName)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  private boolean expectFile(String moneyAccountName) {
    return jdbcClient
        .sql("select expect_file from import_account where money_account_name = :name")
        .param("name", moneyAccountName)
        .query(Boolean.class)
        .single();
  }

  private long insertPerson(String name) {
    return jdbcClient
        .sql("insert into person (name) values (:name) returning person_id")
        .param("name", name)
        .query(Long.class)
        .single();
  }

  /**
   * The account id owned by {@code personId} for {@code currency} — the leaf a person map targets.
   */
  private Long personLeafId(long personId, String currency) {
    return jdbcClient
        .sql(
            "select a.account_id from account a"
                + " join account_owner ao on ao.account_id = a.account_id"
                + " where ao.person_id = :pid and a.currency_code = :cur")
        .param("pid", personId)
        .param("cur", currency)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  private long stagedFileId(String filename) {
    return jdbcClient
        .sql(
            "select import_file_id from import_file where filename = :name order by import_file_id")
        .param("name", filename)
        .query(Long.class)
        .single();
  }

  private String reviewHtml(MockHttpSession session) throws Exception {
    return mockMvc
        .perform(get("/import/review").session(session))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void withoutCampaignTheScreenOffersToStartOne() throws Exception {
    mockMvc
        .perform(get("/import").session(new MockHttpSession()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Start an import session")));
  }

  @Test
  void uploadRoutesToPreviewOfParsedFile() throws Exception {
    MockHttpSession session = openCampaign();

    String token = upload(session, qif("export.qif", DAY_MONTH_BANK), "Current Account");

    mockMvc
        .perform(get("/import/uploads/" + token).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Current Account")))
        .andExpect(content().string(containsString("asset")))
        .andExpect(content().string(containsString("D28/07")))
        .andExpect(content().string(containsString(">day_month<")))
        .andExpect(content().string(containsString("!Type:Bank")));

    assertNothingStaged();
  }

  @Test
  void deducesTheAccountNameFromTheOpeningBalanceRecordAndStagesWithoutTyping() throws Exception {
    MockHttpSession session = openCampaign();

    String token = uploadOnly(session, qif("export.qif", BANK_WITH_OPENING_BALANCE));

    mockMvc
        .perform(get("/import/uploads/" + token).session(session))
        .andExpect(content().string(containsString("Bank24ru-EUR")))
        .andExpect(content().string(containsString("opening-balance record")));

    stage(session, token);

    assertThat(
            jdbcClient
                .sql("select money_account_name from import_file")
                .query(String.class)
                .single())
        .isEqualTo("Bank24ru-EUR");
  }

  @Test
  void promptsForTheAccountNameAndRefusesStagingWhenThereIsNoOpeningBalanceRecord()
      throws Exception {
    MockHttpSession session = openCampaign();

    String token = uploadOnly(session, qif("export.qif", DAY_MONTH_BANK));

    mockMvc
        .perform(get("/import/uploads/" + token).session(session))
        .andExpect(content().string(containsString("State which Money account this file is for")));

    mockMvc
        .perform(post("/import/uploads/" + token + "/stage").session(session))
        .andExpect(redirectedUrl("/import/uploads/" + token));
    assertNothingStaged();

    // Once stated, it stages.
    mockMvc
        .perform(
            post("/import/uploads/" + token)
                .param("moneyAccountName", "Current Account")
                .session(session))
        .andExpect(redirectedUrl("/import/uploads/" + token));
    stage(session, token);
    assertThat(count("import_file")).isEqualTo(1);
  }

  @Test
  void overrideChangesTheEffectiveCharsetAndDateOrder() throws Exception {
    MockHttpSession session = openCampaign();
    String token = upload(session, qif("export.qif", DAY_MONTH_BANK), "Current Account");

    mockMvc
        .perform(
            post("/import/uploads/" + token)
                .param("charset", "windows_1252")
                .param("dateOrder", "month_day")
                .session(session))
        .andExpect(redirectedUrl("/import/uploads/" + token));

    mockMvc
        .perform(get("/import/uploads/" + token).session(session))
        .andExpect(content().string(containsString(">windows_1252<")))
        .andExpect(content().string(containsString(">month_day<")));

    assertNothingStaged();
  }

  @Test
  void rejectedFileShowsItsReasonInThePreview() throws Exception {
    MockHttpSession session = openCampaign();

    String token = upload(session, qif("brokerage.qif", INVESTMENT), "Brokerage");

    mockMvc
        .perform(get("/import/uploads/" + token).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("investment")));

    assertNothingStaged();
  }

  @Test
  void secondFileOfSameNamePromptsReplacementOrCoincidence() throws Exception {
    MockHttpSession session = openCampaign();
    upload(session, qif("export.qif", DAY_MONTH_BANK), "Current Account");

    mockMvc
        .perform(
            multipart("/import/uploads").file(qif("export.qif", DAY_MONTH_BANK)).session(session))
        .andExpect(redirectedUrl("/import"));

    mockMvc
        .perform(get("/import").session(session))
        .andExpect(content().string(containsString("replacement")))
        .andExpect(content().string(containsString("Keep both")));

    // Replace: the old upload is dropped, one pending upload remains.
    MvcResult resolved =
        mockMvc
            .perform(post("/import/uploads/clash").param("resolution", "replace").session(session))
            .andExpect(redirectedUrlPattern("/import/uploads/*"))
            .andReturn();
    String location =
        Objects.requireNonNull(resolved.getResponse().getRedirectedUrl(), "no redirect Location");
    String token = location.substring(location.lastIndexOf('/') + 1);
    mockMvc
        .perform(
            post("/import/uploads/" + token).param("moneyAccountName", "Savings").session(session))
        .andExpect(redirectedUrl("/import/uploads/" + token));

    mockMvc
        .perform(get("/import").session(session))
        .andExpect(content().string(containsString("Savings")))
        .andReturn();

    assertNothingStaged();
    assertThat(location).startsWith("/import/uploads/");
  }

  @Test
  void confirmingThePreviewStagesTheFileItsTransactionsAndItsMapRows() throws Exception {
    MockHttpSession session = openCampaign();

    stageNewFile(session, "export.qif", DAY_MONTH_BANK, "Current Account");

    assertThat(count("import_file")).isEqualTo(1);
    assertThat(count("import_transaction")).isEqualTo(2);
    // two legs per transaction: the category leg plus the synthesised funding leg (§7)
    assertThat(count("import_posting")).isEqualTo(4);
    assertThat(count("import_account")).isEqualTo(1);
    assertThat(count("import_category")).isEqualTo(1);

    mockMvc
        .perform(get("/import").session(session))
        .andExpect(content().string(containsString("Staged files")))
        .andExpect(content().string(containsString("export.qif")))
        .andExpect(content().string(containsString("2 transactions")));
  }

  @Test
  void stagingTwoFilesAccumulatesMapRowsWithoutDuplicatingThem() throws Exception {
    MockHttpSession session = openCampaign();

    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");
    stageNewFile(session, "savings.qif", SAVINGS_WITH_TRANSFER, "Savings");

    // "Current Account" is named by both files, "Food" by both — each maps once.
    assertThat(
            jdbcClient
                .sql("select money_account_name from import_account order by money_account_name")
                .query(String.class)
                .list())
        .containsExactly("Current Account", "Savings");
    assertThat(count("import_category")).isEqualTo(1);
    assertThat(count("import_file")).isEqualTo(2);
  }

  @Test
  void removingStagedFileRemovesExactlyItsRowsAndKeepsTheMaps() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");
    stageNewFile(session, "savings.qif", SAVINGS_WITH_TRANSFER, "Savings");

    mockMvc
        .perform(post("/import/files/" + stagedFileId("current.qif") + "/remove").session(session))
        .andExpect(redirectedUrl("/import"));

    assertThat(jdbcClient.sql("select filename from import_file").query(String.class).list())
        .containsExactly("savings.qif");
    // only savings.qif's rows are left: 2 transactions, 4 legs
    assertThat(count("import_transaction")).isEqualTo(2);
    assertThat(count("import_posting")).isEqualTo(4);
    // the maps both files fed persist for the campaign (§5)
    assertThat(count("import_account")).isEqualTo(2);
    assertThat(count("import_category")).isEqualTo(1);
  }

  @Test
  void replacingStagedFileLeavesOnlyTheNewFileRows() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "export.qif", DAY_MONTH_BANK, "Current Account");

    // Same filename again — parked (redirects to the screen), then "replace" drops the staged rows
    // before the new file stages.
    mockMvc
        .perform(
            multipart("/import/uploads")
                .file(qif("export.qif", SAVINGS_WITH_TRANSFER))
                .session(session))
        .andExpect(redirectedUrl("/import"));
    MvcResult resolved =
        mockMvc
            .perform(post("/import/uploads/clash").param("resolution", "replace").session(session))
            .andExpect(redirectedUrlPattern("/import/uploads/*"))
            .andReturn();
    String location =
        Objects.requireNonNull(resolved.getResponse().getRedirectedUrl(), "no redirect Location");
    String token = location.substring(location.lastIndexOf('/') + 1);
    mockMvc
        .perform(
            post("/import/uploads/" + token).param("moneyAccountName", "Savings").session(session))
        .andExpect(redirectedUrl("/import/uploads/" + token));
    stage(session, token);

    assertThat(count("import_file")).isEqualTo(1);
    assertThat(
            jdbcClient
                .sql("select money_account_name from import_file")
                .query(String.class)
                .single())
        .isEqualTo("Savings");
    assertThat(count("import_transaction")).isEqualTo(2);
  }

  @Test
  void reviewPageShowsPerAccountStatisticsTickedAgainstMoney() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");

    mockMvc
        .perform(get("/import/review").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Per-account statistics")))
        .andExpect(content().string(containsString("Current Account")))
        .andExpect(content().string(containsString("2 transactions")))
        .andExpect(content().string(containsString("net -17,34")))
        .andExpect(content().string(containsString("01.07.2004 – 28.07.2004")));
  }

  @Test
  void reviewNetSumCountsOnlyEachAccountsOwnFundingLegs() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");
    stageNewFile(session, "savings.qif", SAVINGS_WITH_TRANSFER, "Savings");

    // Savings transfers 20.00 to [Current Account]; that mirror leg must not inflate Current
    // Account's net here — it stays its own funding legs' sum.
    mockMvc
        .perform(get("/import/review").session(session))
        .andExpect(content().string(containsString("net -17,34")))
        .andExpect(content().string(containsString("net -28,00")));
  }

  @Test
  void mapsMoneyAccountToExistingHauptbuchAccount() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");
    long giro = insertAccount("Giro", "asset");

    mockMvc
        .perform(
            post("/import/review/accounts/" + mapRowId("Current Account") + "/map")
                .param("accountId", Long.toString(giro))
                .session(session))
        .andExpect(redirectedUrlPattern("/import/review#account-*"));

    assertThat(mappedAccountId("Current Account")).isEqualTo(giro);
    mockMvc
        .perform(get("/import/review").session(session))
        .andExpect(content().string(containsString("→ Giro")));
  }

  @Test
  void accountRowStartsExpandedAndReturnsCollapsedOnceMapped() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");
    long giro = insertAccount("Giro", "asset");
    String openRow = "id=\"account-" + mapRowId("Current Account") + "\" open";

    assertThat(reviewHtml(session)).contains(openRow);

    mockMvc
        .perform(
            post("/import/review/accounts/" + mapRowId("Current Account") + "/map")
                .param("accountId", Long.toString(giro))
                .session(session))
        .andExpect(redirectedUrlPattern("/import/review#account-*"));

    assertThat(reviewHtml(session)).doesNotContain(openRow);
  }

  @Test
  void newAccountCurrencyFieldUsesTheSharedCurrencyPicker() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");
    long rowId = mapRowId("Current Account");

    mockMvc
        .perform(get("/import/review").session(session))
        .andExpect(content().string(containsString("currency-picker__add")))
        .andExpect(
            content().string(containsString("currency-dialog-import-account-currency-" + rowId)));
  }

  @Test
  void mapsMoneyAccountToNewAccountItCreates() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");

    mockMvc
        .perform(
            post("/import/review/accounts/" + mapRowId("Current Account") + "/map")
                .param("newName", "Everyday")
                .param("type", "asset")
                .param("currencyCode", "EUR")
                .session(session))
        .andExpect(redirectedUrlPattern("/import/review#account-*"));

    Long created = mappedAccountId("Current Account");
    assertThat(created).isNotNull();
    assertThat(
            jdbcClient
                .sql("select name from account where account_id = :id")
                .param("id", created)
                .query(String.class)
                .single())
        .isEqualTo("Everyday");
  }

  @Test
  void severalMoneyAccountsCanShareOneHauptbuchAccount() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");
    stageNewFile(session, "savings.qif", SAVINGS_WITH_TRANSFER, "Savings");
    long dump = insertAccount("Everything Else", "asset");

    for (String moneyAccount : new String[] {"Current Account", "Savings"}) {
      mockMvc
          .perform(
              post("/import/review/accounts/" + mapRowId(moneyAccount) + "/map")
                  .param("accountId", Long.toString(dump))
                  .session(session))
          .andExpect(redirectedUrlPattern("/import/review#account-*"));
    }

    assertThat(mappedAccountId("Current Account")).isEqualTo(dump);
    assertThat(mappedAccountId("Savings")).isEqualTo(dump);
  }

  @Test
  void rejectedAccountMappingComesBackToReviewWithReason() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", DAY_MONTH_BANK, "Current Account");

    mockMvc
        .perform(
            post("/import/review/accounts/" + mapRowId("Current Account") + "/map")
                .param("accountId", "999999")
                .session(session))
        .andExpect(redirectedUrlPattern("/import/review#account-*"));

    mockMvc
        .perform(get("/import/review").session(session))
        .andExpect(content().string(containsString("999999")));
    assertThat(mappedAccountId("Current Account")).isNull();
  }

  @Test
  void mapsMoneyAccountToExistingPersonsResolvedLeaf() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", BANK_WITH_PERSON_TRANSFER, "Current Account");
    long max = insertPerson("Max");

    mockMvc
        .perform(
            post("/import/review/accounts/" + mapRowId("Loan to Max") + "/map-person")
                .param("personId", Long.toString(max))
                .param("currencyCode", "EUR")
                .session(session))
        .andExpect(redirectedUrlPattern("/import/review#account-*"));

    // The row now holds Max's EUR leaf as an ordinary account id (import.md §5.4).
    assertThat(mappedAccountId("Loan to Max")).isEqualTo(personLeafId(max, "EUR"));
    mockMvc
        .perform(get("/import/review").session(session))
        .andExpect(content().string(containsString("Max")))
        .andExpect(content().string(containsString("(person)")));
  }

  @Test
  void mapsMoneyAccountToNewPersonProvisioningTheLeaf() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", BANK_WITH_PERSON_TRANSFER, "Current Account");

    mockMvc
        .perform(
            post("/import/review/accounts/" + mapRowId("Loan to Max") + "/map-person")
                .param("newPersonName", "Cousin Bob")
                .param("currencyCode", "EUR")
                .session(session))
        .andExpect(redirectedUrlPattern("/import/review#account-*"));

    Long bob =
        jdbcClient
            .sql("select person_id from person where name = 'Cousin Bob'")
            .query(Long.class)
            .single();
    assertThat(mappedAccountId("Loan to Max")).isEqualTo(personLeafId(bob, "EUR"));
  }

  @Test
  void expectFileToggleFlipsTheFlagBothWays() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", BANK_WITH_PERSON_TRANSFER, "Current Account");

    // A staged file does not clear expect-file (plan c2) — it stays a manual toggle.
    assertThat(expectFile("Loan to Max")).isTrue();

    mockMvc
        .perform(
            post("/import/review/accounts/" + mapRowId("Loan to Max") + "/expect-file")
                .param("expectFile", "false")
                .session(session))
        .andExpect(redirectedUrlPattern("/import/review#account-*"));
    assertThat(expectFile("Loan to Max")).isFalse();

    mockMvc
        .perform(
            post("/import/review/accounts/" + mapRowId("Loan to Max") + "/expect-file")
                .param("expectFile", "true")
                .session(session))
        .andExpect(redirectedUrlPattern("/import/review#account-*"));
    assertThat(expectFile("Loan to Max")).isTrue();
  }

  @Test
  void reviewPageWithNothingStagedSaysSo() throws Exception {
    MockHttpSession session = openCampaign();

    mockMvc
        .perform(get("/import/review").session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Nothing staged yet")));
  }

  @Test
  void reviewPageWithoutCampaignRedirectsToTheScreen() throws Exception {
    mockMvc
        .perform(get("/import/review").session(new MockHttpSession()))
        .andExpect(redirectedUrl("/import"));
  }

  @Test
  void stagingIsRefusedWhileTheDateOrderIsStillAmbiguous() throws Exception {
    MockHttpSession session = openCampaign();
    String token = upload(session, qif("card.qif", AMBIGUOUS_CARD), "Credit Card");

    mockMvc
        .perform(post("/import/uploads/" + token + "/stage").session(session))
        .andExpect(redirectedUrl("/import/uploads/" + token));

    mockMvc
        .perform(get("/import/uploads/" + token).session(session))
        .andExpect(content().string(containsString("DD/MM")));

    assertNothingStaged();
  }

  @Test
  void confirmingAfterTheCampaignWasDiscardedFallsBackToTheScreen() throws Exception {
    MockHttpSession session = openCampaign();
    String token = upload(session, qif("export.qif", DAY_MONTH_BANK), "Current Account");
    mockMvc
        .perform(post("/import/session/discard").session(session))
        .andExpect(redirectedUrl("/import"));

    mockMvc
        .perform(post("/import/uploads/" + token + "/stage").session(session))
        .andExpect(redirectedUrl("/import"));

    assertNothingStaged();
  }

  private void assertNothingStaged() {
    for (String table :
        new String[] {
          "import_file",
          "import_transaction",
          "import_posting",
          "import_account",
          "import_category",
          "import_category_tag"
        }) {
      assertThat(count(table)).as(table).isZero();
    }
  }

  private int count(String table) {
    return jdbcClient.sql("select count(*) from " + table).query(Integer.class).single();
  }
}
