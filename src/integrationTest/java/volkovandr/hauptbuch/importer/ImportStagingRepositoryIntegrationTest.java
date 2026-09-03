package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryTagRepository;
import volkovandr.hauptbuch.importer.repository.ImportFileRepository;
import volkovandr.hauptbuch.importer.repository.ImportPostingRepository;
import volkovandr.hauptbuch.importer.repository.ImportSessionRepository;
import volkovandr.hauptbuch.importer.repository.ImportTransactionRepository;

/**
 * Integration tier (CLAUDE.md §6): row-mapping round-trips for the b3 staging repositories against
 * real Postgres — the plain inserts / selects / deletes, the idempotent map upserts (§5), and the
 * {@code import_file} → transaction → posting cascade (V19). Flyway applies V19; each test is
 * rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportStagingRepositoryIntegrationTest {

  @Autowired ImportSessionRepository importSessionRepository;
  @Autowired ImportFileRepository importFileRepository;
  @Autowired ImportTransactionRepository importTransactionRepository;
  @Autowired ImportPostingRepository importPostingRepository;
  @Autowired ImportAccountRepository importAccountRepository;
  @Autowired ImportCategoryRepository importCategoryRepository;
  @Autowired ImportCategoryTagRepository importCategoryTagRepository;
  @Autowired JdbcClient jdbcClient;

  private long openSession() {
    return importSessionRepository.insertOpen().importSessionId();
  }

  private ImportFile stageFile(long sessionId, String filename) {
    return importFileRepository.insert(
        new ImportFile(
            null, sessionId, filename, "Current Account", "utf_8", "day_month", "asset", 2, null));
  }

  private long stageTransaction(long fileId, String cleared, boolean openingBalance) {
    return importTransactionRepository.insert(
        new ImportTransaction(
            null,
            fileId,
            LocalDate.of(2004, 7, 1),
            "Grocer",
            false,
            "weekly shop",
            "0007",
            cleared,
            openingBalance,
            null,
            null));
  }

  private static ImportPosting leg(
      long transactionId,
      String amount,
      String note,
      String path,
      String account,
      String className,
      boolean funding) {
    return new ImportPosting(
        null, transactionId, new BigDecimal(amount), note, path, account, className, null, funding);
  }

  @Test
  void fileInsertRoundTripsAndListsBySession() {
    long sessionId = openSession();

    ImportFile file = stageFile(sessionId, "export.qif");

    assertThat(file.importFileId()).isNotNull();
    assertThat(file.importSessionId()).isEqualTo(sessionId);
    assertThat(file.sourceFilename()).isEqualTo("export.qif");
    assertThat(file.moneyAccountName()).isEqualTo("Current Account");
    assertThat(file.charset()).isEqualTo("utf_8");
    assertThat(file.dateOrder()).isEqualTo("day_month");
    assertThat(file.proposedAccountType()).isEqualTo("asset");
    assertThat(file.transactionCount()).isEqualTo(2);
    assertThat(file.stagedAt()).isNotNull();

    assertThat(importFileRepository.findBySession(sessionId)).containsExactly(file);
    assertThat(importFileRepository.findById(file.importFileId())).contains(file);
    assertThat(importFileRepository.existsBySessionAndFilename(sessionId, "export.qif")).isTrue();
    assertThat(importFileRepository.existsBySessionAndFilename(sessionId, "other.qif")).isFalse();
  }

  @Test
  void transactionAndPostingRoundTrip() {
    long fileId = stageFile(openSession(), "export.qif").importFileId();

    long transactionId = stageTransaction(fileId, "reconciled", false);
    importPostingRepository.insert(
        leg(transactionId, "12.34", "line memo", "Food", null, "Holiday", false));
    importPostingRepository.insert(
        leg(transactionId, "-12.34", null, null, "Current Account", null, true));

    ImportTransaction transaction = importTransactionRepository.findByFile(fileId).get(0);
    assertThat(transaction.date()).isEqualTo(LocalDate.of(2004, 7, 1));
    assertThat(transaction.payeeText()).isEqualTo("Grocer");
    assertThat(transaction.note()).isEqualTo("weekly shop");
    assertThat(transaction.referenceNumber()).isEqualTo("0007");
    assertThat(transaction.clearedStatus()).isEqualTo("reconciled");
    assertThat(transaction.state()).isEqualTo("ready");
    assertThat(transaction.transactionId()).isNull();

    assertThat(importPostingRepository.findByTransaction(transactionId))
        .satisfiesExactly(
            category -> {
              assertThat(category.amount()).isEqualByComparingTo("12.34");
              assertThat(category.moneyCategoryPath()).isEqualTo("Food");
              assertThat(category.moneyAccountName()).isNull();
              assertThat(category.note()).isEqualTo("line memo");
              assertThat(category.className()).isEqualTo("Holiday");
              assertThat(category.funding()).isFalse();
            },
            funding -> {
              assertThat(funding.amount()).isEqualByComparingTo("-12.34");
              assertThat(funding.moneyCategoryPath()).isNull();
              assertThat(funding.moneyAccountName()).isEqualTo("Current Account");
              assertThat(funding.funding()).isTrue();
            });
  }

  @Test
  void mapUpsertsAreIdempotentAndAccumulatePerSession() {
    long sessionId = openSession();

    importAccountRepository.upsertUnmapped(sessionId, "Current Account");
    importAccountRepository.upsertUnmapped(sessionId, "Current Account");
    importAccountRepository.upsertUnmapped(sessionId, "Savings");
    importCategoryRepository.upsertUnmapped(sessionId, "Audi:Fuel");
    importCategoryRepository.upsertUnmapped(sessionId, "Audi:Fuel");

    assertThat(importAccountRepository.findBySession(sessionId))
        .extracting(ImportAccount::moneyAccountName)
        .containsExactly("Current Account", "Savings");
    assertThat(importAccountRepository.findBySession(sessionId))
        .allSatisfy(
            account -> {
              assertThat(account.accountId()).isNull();
              assertThat(account.personId()).isNull();
              assertThat(account.expectFile()).isTrue();
            });
    assertThat(importCategoryRepository.findBySession(sessionId))
        .extracting(ImportCategory::moneyPath)
        .containsExactly("Audi:Fuel");
  }

  @Test
  void mapToAccountResolvesTheTargetAndIsManyToOne() {
    long sessionId = openSession();
    importAccountRepository.upsertUnmapped(sessionId, "Junk A");
    importAccountRepository.upsertUnmapped(sessionId, "Junk B");
    long junkA = mapRowId(sessionId, "Junk A");
    long junkB = mapRowId(sessionId, "Junk B");
    long account = insertAccount("Everything Else", "asset");

    importAccountRepository.mapToAccount(junkA, account, null);
    importAccountRepository.mapToAccount(junkB, account, "CHF");

    assertThat(importAccountRepository.findBySession(sessionId))
        .satisfiesExactly(
            a -> {
              assertThat(a.moneyAccountName()).isEqualTo("Junk A");
              assertThat(a.accountId()).isEqualTo(account);
              assertThat(a.personId()).isNull();
              assertThat(a.targetCurrencyCode()).isNull();
            },
            b -> {
              assertThat(b.moneyAccountName()).isEqualTo("Junk B");
              assertThat(b.accountId()).isEqualTo(account);
              assertThat(b.targetCurrencyCode()).isEqualTo("CHF");
            });
  }

  @Test
  void expectFileIsToggledPerRow() {
    long sessionId = openSession();
    importAccountRepository.upsertUnmapped(sessionId, "Current Account");
    long rowId = mapRowId(sessionId, "Current Account");

    // Fresh rows expect a file (V19 default); the toggle flips it both ways.
    assertThat(expectFileOf(sessionId, rowId)).isTrue();

    importAccountRepository.setExpectFile(rowId, false);
    assertThat(expectFileOf(sessionId, rowId)).isFalse();

    importAccountRepository.setExpectFile(rowId, true);
    assertThat(expectFileOf(sessionId, rowId)).isTrue();
  }

  private boolean expectFileOf(long sessionId, long importAccountId) {
    return importAccountRepository.findBySession(sessionId).stream()
        .filter(row -> row.importAccountId() == importAccountId)
        .findFirst()
        .orElseThrow()
        .expectFile();
  }

  @Test
  void openingBalanceChoiceRoundTripsForEachOutcome() {
    long sessionId = openSession();
    importAccountRepository.upsertUnmapped(sessionId, "Current Account");
    long rowId = mapRowId(sessionId, "Current Account");

    importAccountRepository.setOpeningBalanceChoice(rowId, "keep_hauptbuch", null);
    assertThat(reconciliationOf(sessionId, rowId))
        .satisfies(
            row -> {
              assertThat(row.openingBalanceChoice()).isEqualTo("keep_hauptbuch");
              assertThat(row.openingBalanceAmount()).isNull();
            });

    importAccountRepository.setOpeningBalanceChoice(rowId, "take_money", null);
    assertThat(reconciliationOf(sessionId, rowId).openingBalanceChoice()).isEqualTo("take_money");

    importAccountRepository.setOpeningBalanceChoice(rowId, "override", new BigDecimal("1234.56"));
    assertThat(reconciliationOf(sessionId, rowId))
        .satisfies(
            row -> {
              assertThat(row.openingBalanceChoice()).isEqualTo("override");
              assertThat(row.openingBalanceAmount()).isEqualByComparingTo("1234.56");
            });
  }

  private ImportAccount reconciliationOf(long sessionId, long importAccountId) {
    return importAccountRepository.findBySession(sessionId).stream()
        .filter(row -> row.importAccountId() == importAccountId)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void deletingFileCascadesToTransactionsAndPostingsButLeavesTheMaps() {
    long sessionId = openSession();
    long fileId = stageFile(sessionId, "export.qif").importFileId();
    long transactionId = stageTransaction(fileId, "unreconciled", false);
    importPostingRepository.insert(leg(transactionId, "5.00", null, "Food", null, null, false));
    importAccountRepository.upsertUnmapped(sessionId, "Current Account");
    importCategoryRepository.upsertUnmapped(sessionId, "Food");

    assertThat(importFileRepository.deleteById(fileId)).isEqualTo(1);

    assertThat(count("import_transaction")).isZero();
    assertThat(count("import_posting")).isZero();
    assertThat(importAccountRepository.findBySession(sessionId)).hasSize(1);
    assertThat(importCategoryRepository.findBySession(sessionId)).hasSize(1);
  }

  @Test
  void deleteBySessionAndFilenameRemovesEverySameNamedFile() {
    long sessionId = openSession();
    stageFile(sessionId, "export.qif");
    stageFile(sessionId, "export.qif");
    stageFile(sessionId, "keep.qif");

    assertThat(importFileRepository.deleteBySessionAndFilename(sessionId, "export.qif"))
        .isEqualTo(2);

    assertThat(importFileRepository.findBySession(sessionId))
        .extracting(ImportFile::sourceFilename)
        .containsExactly("keep.qif");
  }

  @Test
  void mapToCategoryResolvesTheTargetAndIsManyToOne() {
    long sessionId = openSession();
    importCategoryRepository.upsertUnmapped(sessionId, "Audi:Fuel");
    importCategoryRepository.upsertUnmapped(sessionId, "Audi:Repair");
    long fuel = categoryRowId(sessionId, "Audi:Fuel");
    long repair = categoryRowId(sessionId, "Audi:Repair");
    long category = insertAccount("Car", "expense");

    importCategoryRepository.mapToCategory(fuel, category);
    importCategoryRepository.mapToCategory(repair, category);

    assertThat(importCategoryRepository.findBySession(sessionId))
        .allSatisfy(row -> assertThat(row.accountId()).isEqualTo(category));
  }

  @Test
  void categoryTagJunctionRoundTripsAndAccumulatesPerSession() {
    long sessionId = openSession();
    importCategoryRepository.upsertUnmapped(sessionId, "Audi:Fuel");
    importCategoryRepository.upsertUnmapped(sessionId, "Audi:Repair");
    long fuel = categoryRowId(sessionId, "Audi:Fuel");
    long repair = categoryRowId(sessionId, "Audi:Repair");
    long audi = insertTag("Audi");
    long holiday = insertTag("Holiday");

    importCategoryTagRepository.addTag(fuel, audi);
    importCategoryTagRepository.addTag(fuel, audi); // idempotent
    importCategoryTagRepository.addTag(fuel, holiday);
    importCategoryTagRepository.addTag(repair, audi);

    assertThat(importCategoryTagRepository.tagIdsFor(fuel)).containsExactly(audi, holiday);
    assertThat(importCategoryTagRepository.tagIdsBySession(sessionId))
        .containsOnlyKeys(fuel, repair)
        .satisfies(byRow -> assertThat(byRow.get(repair)).containsExactly(audi));

    importCategoryTagRepository.clearTags(fuel);
    assertThat(importCategoryTagRepository.tagIdsFor(fuel)).isEmpty();
    assertThat(importCategoryTagRepository.tagIdsBySession(sessionId)).containsOnlyKeys(repair);
  }

  private long categoryRowId(long sessionId, String moneyPath) {
    return importCategoryRepository.findBySession(sessionId).stream()
        .filter(row -> row.moneyPath().equals(moneyPath))
        .findFirst()
        .orElseThrow()
        .importCategoryId();
  }

  private long insertTag(String name) {
    return jdbcClient
        .sql("insert into tag (name) values (:name) returning tag_id")
        .param("name", name)
        .query(Long.class)
        .single();
  }

  private long mapRowId(long sessionId, String moneyAccountName) {
    return importAccountRepository.findBySession(sessionId).stream()
        .filter(row -> row.moneyAccountName().equals(moneyAccountName))
        .findFirst()
        .orElseThrow()
        .importAccountId();
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

  private int count(String table) {
    return jdbcClient.sql("select count(*) from " + table).query(Integer.class).single();
  }
}
