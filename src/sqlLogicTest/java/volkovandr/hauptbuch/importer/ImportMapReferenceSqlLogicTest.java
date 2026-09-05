package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link ImportAccountRepository#findReferencedBySession} and {@link
 * ImportCategoryRepository#findReferencedBySession} — the orphan-map-row scoping the e4 issues list
 * and commit gate read (import.md §9, plan e4's "orphan map rows"). A map row persists across a
 * file removal or replacement (import.md §2, §5); these two queries tell which rows a
 * <strong>live</strong> staged file or posting still references, so a name or path a removed file
 * left behind is not mistaken for a real unmapped gap.
 *
 * <p>Crafted staging rows via raw {@link JdbcClient}; the queries under test are the real
 * repositories. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportMapReferenceSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired ImportAccountRepository importAccountRepository;
  @Autowired ImportCategoryRepository importCategoryRepository;

  private long openSession() {
    return jdbcClient
        .sql("insert into import_session (state) values ('open') returning import_session_id")
        .query(Long.class)
        .single();
  }

  private long account(String name) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, 'asset', 'EUR')"
                + " returning account_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  private void mapAccount(long sessionId, String moneyAccountName, Long accountId) {
    jdbcClient
        .sql(
            """
            insert into import_account (import_session_id, money_account_name, account_id)
            values (:s, :n, :a)
            on conflict (import_session_id, money_account_name)
              do update set account_id = excluded.account_id
            """)
        .param("s", sessionId)
        .param("n", moneyAccountName)
        .param("a", accountId)
        .update();
  }

  private void mapCategory(long sessionId, String moneyPath, Long accountId) {
    jdbcClient
        .sql(
            """
            insert into import_category (import_session_id, money_path, account_id)
            values (:s, :p, :a)
            on conflict (import_session_id, money_path)
              do update set account_id = excluded.account_id
            """)
        .param("s", sessionId)
        .param("p", moneyPath)
        .param("a", accountId)
        .update();
  }

  private long stageFile(long sessionId, String moneyAccountName) {
    return jdbcClient
        .sql(
            """
            insert into import_file
              (import_session_id, filename, money_account_name, charset, date_order)
            values (:s, 'export.qif', :n, 'utf_8', 'day_month')
            returning import_file_id
            """)
        .param("s", sessionId)
        .param("n", moneyAccountName)
        .query(Long.class)
        .single();
  }

  private long stageTransaction(long fileId) {
    return jdbcClient
        .sql(
            "insert into import_transaction (import_file_id, date) values (:f, '2020-01-01')"
                + " returning import_transaction_id")
        .param("f", fileId)
        .query(Long.class)
        .single();
  }

  private void categoryLeg(long transactionId, String moneyCategoryPath) {
    jdbcClient
        .sql(
            """
            insert into import_posting
              (import_transaction_id, amount, money_category_path, funding)
            values (:t, 10.00, :p, false)
            """)
        .param("t", transactionId)
        .param("p", moneyCategoryPath)
        .update();
  }

  private void transferLeg(long transactionId, String moneyAccountName) {
    jdbcClient
        .sql(
            """
            insert into import_posting
              (import_transaction_id, amount, money_account_name, funding)
            values (:t, -10.00, :n, false)
            """)
        .param("t", transactionId)
        .param("n", moneyAccountName)
        .update();
  }

  // ── accounts ─────────────────────────────────────────────────────────────

  @Test
  void referencedAccountsIncludeFilesOwnAccountEvenWhenUnmapped() {
    long session = openSession();
    stageFile(session, "Current");
    mapAccount(session, "Current", null);

    assertThat(importAccountRepository.findReferencedBySession(session))
        .extracting(ImportAccount::moneyAccountName)
        .containsExactly("Current");
  }

  @Test
  void referencedAccountsIncludeTransferCounterpartyNamedOnlyByPosting() {
    long session = openSession();
    long fileId = stageFile(session, "Current");
    long txnId = stageTransaction(fileId);
    transferLeg(txnId, "Savings");
    mapAccount(session, "Current", account("Giro"));
    mapAccount(session, "Savings", null);

    assertThat(importAccountRepository.findReferencedBySession(session))
        .extracting(ImportAccount::moneyAccountName)
        .containsExactly("Current", "Savings");
  }

  @Test
  void referencedAccountsExcludesAnOrphanRowNoLiveFileOrPostingNamesAnyMore() {
    long session = openSession();
    stageFile(session, "Current");
    mapAccount(session, "Current", null);
    // "Old Cash" was left behind by a since-removed file — no import_file or import_posting names
    // it any more, but the map row (import.md §5) persists for the campaign.
    mapAccount(session, "Old Cash", null);

    assertThat(importAccountRepository.findReferencedBySession(session))
        .extracting(ImportAccount::moneyAccountName)
        .containsExactly("Current");
    // findBySession, unscoped, still returns the orphan — the two methods answer different
    // questions.
    assertThat(importAccountRepository.findBySession(session))
        .extracting(ImportAccount::moneyAccountName)
        .containsExactlyInAnyOrder("Current", "Old Cash");
  }

  @Test
  void referencedAccountsScopesToTheGivenSession() {
    long session = openSession();
    long other = openSessionDiscardedLater();
    stageFile(other, "Elsewhere");
    mapAccount(other, "Elsewhere", null);
    stageFile(session, "Current");
    mapAccount(session, "Current", null);

    assertThat(importAccountRepository.findReferencedBySession(session))
        .extracting(ImportAccount::moneyAccountName)
        .containsExactly("Current");
  }

  private long openSessionDiscardedLater() {
    return jdbcClient
        .sql("insert into import_session (state) values ('discarded') returning import_session_id")
        .query(Long.class)
        .single();
  }

  // ── categories ───────────────────────────────────────────────────────────

  @Test
  void referencedCategoriesIncludePathStagedPostingCarries() {
    long session = openSession();
    long fileId = stageFile(session, "Current");
    long txnId = stageTransaction(fileId);
    categoryLeg(txnId, "Food");
    mapCategory(session, "Food", null);

    assertThat(importCategoryRepository.findReferencedBySession(session))
        .extracting(ImportCategory::moneyPath)
        .containsExactly("Food");
  }

  @Test
  void referencedCategoriesExcludesAnOrphanRowNoLivePostingNamesAnyMore() {
    long session = openSession();
    long fileId = stageFile(session, "Current");
    long txnId = stageTransaction(fileId);
    categoryLeg(txnId, "Food");
    mapCategory(session, "Food", null);
    // "Old Hobby" was left behind by a since-removed file, same as the account case.
    mapCategory(session, "Old Hobby", account("Hobby"));

    assertThat(importCategoryRepository.findReferencedBySession(session))
        .extracting(ImportCategory::moneyPath)
        .containsExactly("Food");
    assertThat(importCategoryRepository.findBySession(session))
        .extracting(ImportCategory::moneyPath)
        .containsExactlyInAnyOrder("Food", "Old Hobby");
  }
}
