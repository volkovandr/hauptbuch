package volkovandr.hauptbuch.importer;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.backup.BackupFile;
import volkovandr.hauptbuch.backup.BackupKind;
import volkovandr.hauptbuch.backup.BackupService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (import.md §2, §12; CLAUDE.md §6): the import commit screen driven through
 * {@link ImportCommitController} (plan f2b) — the gate, the backup ceremony, and the poll fragment.
 * The worker and the backup runner are mocked ({@link ImportCommitWorker}, {@link BackupService});
 * the commit engine itself has its own end-to-end test ({@link ImportCommitIntegrationTest}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportCommitScreenIntegrationTest {

  private static final String COMMIT = "/import/commit";

  private static final String DAY_MONTH_BANK =
      """
      !Type:Bank
      D15/07'2004
      T-12.34
      PShopBbb
      LFood
      ^
      """;

  @Autowired MockMvc mockMvc;
  @Autowired SettingsService settingsService;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbcClient;
  @MockitoBean ImportCommitWorker importCommitWorker;
  @MockitoBean BackupService backupService;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency("EUR");
    when(importCommitWorker.progressFor(anyLong())).thenReturn(ImportCommitProgress.IDLE);
    when(backupService.list()).thenReturn(List.of());
  }

  @Test
  void redirectsToTheCampaignScreenWithNoOpenSession() throws Exception {
    mockMvc.perform(get(COMMIT)).andExpect(redirectedUrl("/import"));
  }

  @Test
  void showsTheGateLockedUntilTheReviewIsClear() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc.perform(post("/import/session").session(session));
    uploadAndStage(session); // staged but nothing mapped

    String html =
        mockMvc
            .perform(get(COMMIT).session(session))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html).contains("The review is not clear");
    assertThat(html).doesNotContain("Commit the campaign"); // the button lives behind the gate
    assertThat(html).contains("Open the review");

    mockMvc
        .perform(post(COMMIT).session(session))
        .andExpect(
            flash()
                .attribute("error", org.hamcrest.Matchers.containsString("cannot be committed")));
    verify(importCommitWorker, never()).start(anyLong());
  }

  @Test
  void refusesTheCommitWithNoFreshSafetyBackup() throws Exception {
    MockHttpSession session = mappedCampaign();
    mockMvc.perform(post("/import/review/duplicate-scan/run").session(session));
    // No manual backup on file → backupService.list() is empty (the @BeforeEach default).

    String html =
        mockMvc
            .perform(get(COMMIT).session(session))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html).contains("No safety backup has been taken");

    mockMvc.perform(post(COMMIT).session(session)).andExpect(flash().attributeExists("error"));
    verify(importCommitWorker, never()).start(anyLong());
  }

  @Test
  void startsTheWorkerWhenTheGateIsOpenAndTheBackupIsCurrent() throws Exception {
    MockHttpSession session = mappedCampaign();
    mockMvc.perform(post("/import/review/duplicate-scan/run").session(session));
    when(backupService.list())
        .thenReturn(
            List.of(
                new BackupFile(
                    "import-safety.dump",
                    BackupKind.MANUAL,
                    LocalDateTime.now().plusMinutes(1),
                    1L)));
    when(importCommitWorker.start(anyLong())).thenReturn(true);

    mockMvc.perform(post(COMMIT).session(session)).andExpect(redirectedUrl(COMMIT));

    verify(importCommitWorker).start(anyLong());
  }

  @Test
  void takesTheSafetyBackupOnRequest() throws Exception {
    MockHttpSession session = mappedCampaign();
    when(backupService.take(BackupKind.MANUAL))
        .thenReturn(
            new BackupFile("import-safety.dump", BackupKind.MANUAL, LocalDateTime.now(), 1L));

    mockMvc
        .perform(post(COMMIT + "/backup").session(session))
        .andExpect(redirectedUrl(COMMIT))
        .andExpect(
            flash()
                .attribute("backup", org.hamcrest.Matchers.containsString("import-safety.dump")));
    verify(backupService).take(BackupKind.MANUAL);
  }

  @Test
  void statusFragmentReflectsTheWorkerState() throws Exception {
    when(importCommitWorker.progress())
        .thenReturn(new ImportCommitProgress("running", 1L, 40, 12, null));
    assertThat(fragment()).contains("12 / 40").contains("import-commit-poll");

    when(importCommitWorker.progress())
        .thenReturn(ImportCommitProgress.ofDone(1L, "Committed 40 transaction(s), skipped 0."));
    String done = fragment();
    assertThat(done).contains("Committed 40 transaction(s)").doesNotContain("import-commit-poll");
    // The out-of-band swap that removes the stale backup/commit buttons.
    assertThat(done).contains("id=\"import-commit-ceremony\"").contains("hx-swap-oob");

    when(importCommitWorker.progress())
        .thenReturn(ImportCommitProgress.ofFailed(1L, "no rate for CHF"));
    String failed = fragment();
    assertThat(failed).contains("The commit failed").contains("no rate for CHF");
    assertThat(failed).doesNotContain("hx-swap-oob"); // buttons stay so the owner can retry
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private String fragment() throws Exception {
    return mockMvc.perform(get(COMMIT + "/status")).andReturn().getResponse().getContentAsString();
  }

  private MockHttpSession mappedCampaign() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc.perform(post("/import/session").session(session));
    uploadAndStage(session);
    long giro = insertLeaf("Giro AAA", "asset");
    long food = insertLeaf("Food", "expense");
    mockMvc.perform(
        post("/import/review/accounts/" + accountRowId() + "/map")
            .param("accountId", Long.toString(giro))
            .session(session));
    mockMvc.perform(
        post("/import/review/categories/" + categoryRowId() + "/map")
            .param("accountId", Long.toString(food))
            .session(session));
    return session;
  }

  private void uploadAndStage(MockHttpSession session) throws Exception {
    String location =
        requireNonNull(
            mockMvc
                .perform(
                    multipart("/import/uploads")
                        .file(
                            new MockMultipartFile(
                                "file",
                                "bank.qif",
                                "text/plain",
                                DAY_MONTH_BANK.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .session(session))
                .andReturn()
                .getResponse()
                .getRedirectedUrl(),
            "no redirect Location");
    String token = location.substring(location.lastIndexOf('/') + 1);
    mockMvc.perform(
        post("/import/uploads/" + token).param("moneyAccountName", "BankAaa").session(session));
    mockMvc.perform(post("/import/uploads/" + token + "/stage").session(session));
  }

  private long insertLeaf(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, 'EUR')"
                + " returning account_id")
        .param("n", name)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  private long accountRowId() {
    return jdbcClient
        .sql("select import_account_id from import_account where money_account_name = 'BankAaa'")
        .query(Long.class)
        .single();
  }

  private long categoryRowId() {
    return jdbcClient
        .sql("select import_category_id from import_category where money_path = 'Food'")
        .query(Long.class)
        .single();
  }
}
