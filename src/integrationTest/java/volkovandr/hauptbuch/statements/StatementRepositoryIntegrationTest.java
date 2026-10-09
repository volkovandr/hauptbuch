package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.statements.repository.StatementLineRepository;
import volkovandr.hauptbuch.statements.repository.StatementProfileRepository;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * Integration tier (CLAUDE.md §6): V33 applies, and each statement repository method round-trips
 * its rows against real Postgres — the profile's whole dialect and column map, a statement's
 * header, a line's fields and problem. Also pins the schema's own guards on {@code
 * statement_match}: one match per line, and never two lines of one statement on one posting. Each
 * test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementRepositoryIntegrationTest {

  @Autowired StatementProfileRepository profiles;
  @Autowired StatementRepository statements;
  @Autowired StatementLineRepository lines;
  @Autowired JdbcClient jdbcClient;

  private long accountId;
  private long profileId;

  @BeforeEach
  void seed() {
    accountId =
        jdbcClient
            .sql(
                "insert into account (name, type, currency_code) values ('BankAaa-EUR', 'asset',"
                    + " 'EUR') returning account_id")
            .query(Long.class)
            .single();
    profileId = profiles.insert(profile("BankAaa CSV"));
  }

  private static StatementProfile profile(String name) {
    return new StatementProfile(
        null,
        name,
        "csv",
        7,
        2,
        "a note",
        ";",
        "\"",
        "ISO-8859-1",
        2,
        true,
        ",",
        "dd.MM.yyyy",
        "debit_credit",
        "Booking",
        "Value",
        null,
        "Debit",
        "Credit",
        "Cur",
        "Who",
        "Text",
        "Cat",
        "IBAN",
        null);
  }

  @Test
  void profileRoundTripsItsWholeDialectAndColumnMap() {
    StatementProfile read = profiles.findById(profileId).orElseThrow();

    assertThat(read)
        .usingRecursiveComparison()
        .ignoringFields("statementProfileId")
        .isEqualTo(profile("BankAaa CSV"));
    assertThat(read.statementProfileId()).isEqualTo(profileId);
  }

  @Test
  void profileUpdateOverwritesAndSkipsDeletedProfile() {
    assertThat(profiles.update(profileId, profile("Renamed"))).isEqualTo(1);
    assertThat(profiles.findById(profileId).orElseThrow().name()).isEqualTo("Renamed");

    assertThat(profiles.softDelete(profileId)).isEqualTo(1);
    assertThat(profiles.softDelete(profileId)).isZero();
    assertThat(profiles.update(profileId, profile("Again"))).isZero();
    assertThat(profiles.findById(profileId).orElseThrow().deletedAt()).isNotNull();
  }

  @Test
  void findLiveListsOnlyLiveProfilesByName() {
    profiles.insert(profile("aaa first"));
    profiles.softDelete(profiles.insert(profile("zzz gone")));

    assertThat(profiles.findLive())
        .extracting(StatementProfile::name)
        .containsSubsequence("aaa first", "BankAaa CSV")
        .doesNotContain("zzz gone");
  }

  @Test
  void profileRefusesUnknownFormatOrSignMode() {
    StatementProfile bad =
        new StatementProfile(
            null, "x", "xml", 1, 1, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

    assertThatThrownBy(() -> profiles.insert(bad))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void statementRoundTripsItsHeaderAndSoftDeletes() {
    long id =
        statements.insert(
            profileId,
            accountId,
            "2026-05.csv",
            "2026/05/x.csv",
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31));

    Statement read = statements.findById(id).orElseThrow();
    assertThat(read.accountId()).isEqualTo(accountId);
    assertThat(read.state()).isEqualTo("processed");
    assertThat(read.periodStart()).isEqualTo(LocalDate.of(2026, 5, 1));
    assertThat(read.openingBalance()).isNull();
    assertThat(read.deletedAt()).isNull();

    assertThat(
            statements.updateHeader(
                id,
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 6, 1),
                new BigDecimal("10.5"),
                new BigDecimal("20.25")))
        .isEqualTo(1);
    Statement edited = statements.findById(id).orElseThrow();
    assertThat(edited.periodStart()).isEqualTo(LocalDate.of(2026, 4, 30));
    assertThat(edited.openingBalance()).isEqualByComparingTo("10.50");
    assertThat(edited.closingBalance()).isEqualByComparingTo("20.25");

    assertThat(statements.softDelete(id)).isEqualTo(1);
    assertThat(statements.softDelete(id)).isZero();
    assertThat(statements.updateHeader(id, null, null, null, null)).isZero();
    assertThat(statements.findById(id).orElseThrow().deletedAt()).isNotNull();
  }

  @Test
  void lineRoundTripsAndKeepsFileOrder() {
    long statementId = statements.insert(profileId, accountId, "a.csv", "p", null, null);
    lines.insert(
        statementId,
        new StatementLine(
            null,
            1,
            LocalDate.of(2026, 5, 3),
            null,
            new BigDecimal("-2.50"),
            "ShopBbb",
            "Card",
            "Cat",
            "raw 2",
            null));
    lines.insert(
        statementId,
        new StatementLine(
            null, 0, null, null, null, null, null, null, "raw 1", "Unreadable booking date 'x'"));

    List<StatementLine> read = lines.findByStatement(statementId);

    assertThat(read).extracting(StatementLine::rawText).containsExactly("raw 1", "raw 2");
    assertThat(read.get(0).problem()).isEqualTo("Unreadable booking date 'x'");
    assertThat(read.get(0).amount()).isNull();
    assertThat(read.get(1).amount()).isEqualByComparingTo("-2.50");
    assertThat(read.get(1).counterparty()).isEqualTo("ShopBbb");
    assertThat(read.get(1).bankCategory()).isEqualTo("Cat");
  }

  @Test
  void lineUpdateChangesTheEditableFieldsOnlyWithinItsStatement() {
    long statementId = statements.insert(profileId, accountId, "a.csv", "p", null, null);
    long otherId = statements.insert(profileId, accountId, "b.csv", "p", null, null);
    long lineId =
        lines.insert(
            statementId,
            new StatementLine(
                null, 0, null, null, null, "A", "B", "C", "raw", "Unreadable booking date 'x'"));
    StatementLine fixed =
        new StatementLine(
            lineId,
            0,
            LocalDate.of(2026, 5, 3),
            LocalDate.of(2026, 5, 4),
            new BigDecimal("5"),
            "A2",
            "B2",
            "C2",
            "ignored",
            null);

    assertThat(lines.update(otherId, fixed)).isZero();
    assertThat(lines.update(statementId, fixed)).isEqualTo(1);

    StatementLine read = lines.findByStatement(statementId).get(0);
    assertThat(read.bookingDate()).isEqualTo(LocalDate.of(2026, 5, 3));
    assertThat(read.valueDate()).isEqualTo(LocalDate.of(2026, 5, 4));
    assertThat(read.amount()).isEqualByComparingTo("5");
    assertThat(read.counterparty()).isEqualTo("A2");
    assertThat(read.problem()).isNull();
    assertThat(read.rawText()).isEqualTo("raw");
  }

  @Test
  void matchIsUniquePerLineAndPerPostingWithinStatement() {
    long statementId = statements.insert(profileId, accountId, "a.csv", "p", null, null);
    long lineOne =
        lines.insert(
            statementId, new StatementLine(null, 0, null, null, null, null, null, null, "1", null));
    long postingId = posting();
    long otherPosting = posting();
    insertMatch(lineOne, statementId, postingId);

    assertThatThrownBy(() -> insertMatch(lineOne, statementId, otherPosting))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void twoLinesOfOneStatementCannotSharePosting() {
    long statementId = statements.insert(profileId, accountId, "a.csv", "p", null, null);
    long lineOne =
        lines.insert(
            statementId, new StatementLine(null, 0, null, null, null, null, null, null, "1", null));
    long lineTwo =
        lines.insert(
            statementId, new StatementLine(null, 1, null, null, null, null, null, null, "2", null));
    long postingId = posting();
    insertMatch(lineOne, statementId, postingId);

    assertThatThrownBy(() -> insertMatch(lineTwo, statementId, postingId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deletingPostingRemovesItsMatch() {
    long statementId = statements.insert(profileId, accountId, "a.csv", "p", null, null);
    long lineId =
        lines.insert(
            statementId, new StatementLine(null, 0, null, null, null, null, null, null, "1", null));
    long postingId = posting();
    insertMatch(lineId, statementId, postingId);

    jdbcClient.sql("delete from posting where posting_id = :id").param("id", postingId).update();

    assertThat(jdbcClient.sql("select count(*) from statement_match").query(Long.class).single())
        .isZero();
  }

  private void insertMatch(long lineId, long statementId, long postingId) {
    jdbcClient
        .sql(
            "insert into statement_match (statement_line_id, statement_id, posting_id) values"
                + " (:line, :statement, :posting)")
        .param("line", lineId)
        .param("statement", statementId)
        .param("posting", postingId)
        .update();
  }

  private long posting() {
    long transactionId =
        jdbcClient
            .sql(
                "insert into transaction (date) values (date '2026-05-02')"
                    + " returning transaction_id")
            .query(Long.class)
            .single();
    return jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, reconciliation) values"
                + " (:t, :a, 5, 'reconciled') returning posting_id")
        .param("t", transactionId)
        .param("a", accountId)
        .query(Long.class)
        .single();
  }

  @Test
  void pdfStatementRoundTripsItsTextAndStaysEditableUntilParsed() {
    long statementId =
        statements.insertPdf(profileId, accountId, "may.pdf", "2026/05/may.pdf", "masked text");

    assertThat(statements.findSentText(statementId)).contains("masked text");
    assertThat(statements.findById(statementId).orElseThrow().state()).isEqualTo("new");

    assertThat(statements.updateSentText(statementId, "edited text")).isEqualTo(1);
    assertThat(statements.findSentText(statementId)).contains("edited text");

    jdbcClient.sql("update statement set state = 'processed'").update();
    assertThat(statements.updateSentText(statementId, "too late")).isZero();
    assertThat(statements.findSentText(statementId)).contains("edited text");
  }

  @Test
  void csvStatementHasNoSentText() {
    long statementId =
        statements.insert(profileId, accountId, "may.csv", "2026/05/may.csv", null, null);

    assertThat(statements.findSentText(statementId)).isEmpty();
    assertThat(statements.updateSentText(statementId, "nope")).isZero();
  }
}
