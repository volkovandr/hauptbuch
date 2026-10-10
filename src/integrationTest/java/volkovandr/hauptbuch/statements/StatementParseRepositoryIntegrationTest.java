package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.statements.repository.OriginalCharge;
import volkovandr.hauptbuch.statements.repository.ParseUsage;
import volkovandr.hauptbuch.statements.repository.StatementLineRepository;
import volkovandr.hauptbuch.statements.repository.StatementProfileRepository;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * Integration tier (CLAUDE.md §6): the repository methods of a statement parse (slice e2)
 * round-trip against real Postgres — the claim, the three outcomes (processed, failed with the raw
 * body kept, failed with the reason only), the orphan sweep, and the parsed line with its foreign
 * charge. Each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementParseRepositoryIntegrationTest {

  private static final ParseUsage USAGE =
      new ParseUsage(1000, 200, 30, 40, new BigDecimal("0.0123"));

  @Autowired StatementProfileRepository profiles;
  @Autowired StatementRepository statements;
  @Autowired StatementLineRepository lines;
  @Autowired JdbcClient jdbcClient;

  private long statementId;

  @BeforeEach
  void seed() {
    long accountId =
        jdbcClient
            .sql(
                "insert into account (name, type, currency_code) values ('BankBbb-EUR', 'asset',"
                    + " 'EUR') returning account_id")
            .query(Long.class)
            .single();
    long profileId =
        profiles.insert(
            new StatementProfile(
                null,
                "BankBbb PDF",
                "pdf",
                10,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    statementId = statements.insertPdf(profileId, accountId, "may.pdf", "2026/05/x.pdf", "text");
  }

  @Test
  void claimMovesNewStatementToProcessingOnce() {
    assertThat(statements.claimForParse(statementId)).isTrue();
    assertThat(statements.findById(statementId).orElseThrow().state()).isEqualTo("processing");

    assertThat(statements.claimForParse(statementId)).isFalse();
  }

  @Test
  void failedStatementCanBeClaimedAgainAndItsErrorIsCleared() {
    statements.markFailed(statementId, "401 unauthorized");
    assertThat(statements.findParseError(statementId)).hasValue("401 unauthorized");

    assertThat(statements.claimForParse(statementId)).isTrue();

    assertThat(statements.findParseError(statementId)).isEmpty();
  }

  @Test
  void csvOrDeletedStatementCannotBeClaimed() {
    long csvId =
        statements.insert(
            jdbcClient
                .sql("select statement_profile_id from statement where statement_id = :id")
                .param("id", statementId)
                .query(Long.class)
                .single(),
            jdbcClient
                .sql("select account_id from statement where statement_id = :id")
                .param("id", statementId)
                .query(Long.class)
                .single(),
            "x.csv",
            "x.csv",
            null,
            null);
    assertThat(statements.claimForParse(csvId)).isFalse();

    statements.softDelete(statementId);
    assertThat(statements.claimForParse(statementId)).isFalse();
  }

  @Test
  void markProcessedStoresTheHeaderRawBodyUsageAndFrozenCost() {
    statements.claimForParse(statementId);

    statements.markProcessed(
        statementId,
        LocalDate.of(2026, 5, 1),
        LocalDate.of(2026, 5, 31),
        new BigDecimal("1200.50"),
        new BigDecimal("1138.00"),
        USAGE,
        "raw toon");

    Statement read = statements.findById(statementId).orElseThrow();
    assertThat(read.state()).isEqualTo("processed");
    assertThat(read.periodStart()).isEqualTo(LocalDate.of(2026, 5, 1));
    assertThat(read.periodEnd()).isEqualTo(LocalDate.of(2026, 5, 31));
    assertThat(read.openingBalance()).isEqualByComparingTo("1200.50");
    assertThat(read.closingBalance()).isEqualByComparingTo("1138.00");
    Map<String, Object> stored =
        jdbcClient
            .sql(
                "select parse_raw, tokens_in, tokens_out, tokens_cache_write, tokens_cache_read,"
                    + " parse_cost from statement where statement_id = :id")
            .param("id", statementId)
            .query()
            .singleRow();
    assertThat(stored.get("parse_raw")).isEqualTo("raw toon");
    assertThat(stored.get("tokens_in")).isEqualTo(1000);
    assertThat(stored.get("tokens_out")).isEqualTo(200);
    assertThat(stored.get("tokens_cache_write")).isEqualTo(30);
    assertThat(stored.get("tokens_cache_read")).isEqualTo(40);
    assertThat((BigDecimal) stored.get("parse_cost")).isEqualByComparingTo("0.0123");
  }

  @Test
  void markFailedWithResultKeepsTheRawBodyAndTheUsageForTheRepair() {
    statements.claimForParse(statementId);

    statements.markFailedWithResult(statementId, "Could not decode", USAGE, "broken toon");

    assertThat(statements.findById(statementId).orElseThrow().state()).isEqualTo("failed");
    assertThat(statements.findParseError(statementId)).hasValue("Could not decode");
    assertThat(
            jdbcClient
                .sql("select parse_raw from statement where statement_id = :id")
                .param("id", statementId)
                .query(String.class)
                .single())
        .isEqualTo("broken toon");
  }

  @Test
  void failedStatementTextIsStillEditable() {
    statements.markFailed(statementId, "down");

    assertThat(statements.updateSentText(statementId, "edited")).isEqualTo(1);
  }

  @Test
  void theOrphanSweepFailsOnlyProcessingStatements() {
    statements.claimForParse(statementId);

    assertThat(statements.failOrphanedProcessing("restart")).isGreaterThanOrEqualTo(1);

    assertThat(statements.findById(statementId).orElseThrow().state()).isEqualTo("failed");
    assertThat(statements.findParseError(statementId)).hasValue("restart");
    assertThat(statements.failOrphanedProcessing("restart")).isZero();
  }

  @Test
  void insertParsedKeepsTheForeignChargeAndFlagsAnUnknownCurrency() {
    StatementLine line =
        new StatementLine(
            null,
            0,
            LocalDate.of(2026, 5, 9),
            null,
            new BigDecimal("-50.00"),
            "ShopBbb",
            "Card 55.00 USD",
            "Shopping",
            "raw",
            null);

    long withUsd =
        lines.insertParsed(
            statementId, line, new BigDecimal("55.00"), "USD", new BigDecimal("1.1"));
    long withUnknown =
        lines.insertParsed(
            statementId, line, new BigDecimal("55.00"), "QQQ", new BigDecimal("1.1"));
    long without = lines.insertParsed(statementId, line, null, null, null);

    assertThat(originalCurrency(withUsd)).isEqualTo("USD");
    assertThat(originalCurrency(withUnknown)).isNull();
    assertThat(
            jdbcClient
                .sql("select problem from statement_line where statement_line_id = :id")
                .param("id", withUnknown)
                .query(String.class)
                .single())
        .contains("QQQ");
    assertThat(originalCurrency(without)).isNull();
    assertThat(
            jdbcClient
                .sql("select original_amount from statement_line where statement_line_id = :id")
                .param("id", withUsd)
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("55.00");
    assertThat(lines.findByStatement(statementId)).hasSize(3);
  }

  @Test
  void deleteByStatementRemovesOnlyThatStatementsLines() {
    StatementLine line =
        new StatementLine(
            null, 0, LocalDate.of(2026, 5, 9), null, BigDecimal.ONE, null, null, null, null, null);
    lines.insertParsed(statementId, line, null, null, null);

    assertThat(lines.deleteByStatement(statementId)).isEqualTo(1);
    assertThat(lines.findByStatement(statementId)).isEmpty();
  }

  @Test
  void findOriginalChargeReadsTheForeignAmountAndCurrencyOnlyWhenBothAreKnown() {
    StatementLine line =
        new StatementLine(
            null,
            0,
            LocalDate.of(2026, 5, 9),
            null,
            new BigDecimal("-50.00"),
            "c",
            "d",
            "b",
            "raw",
            null);
    long foreign =
        lines.insertParsed(statementId, line, new BigDecimal("-55.00"), "USD", BigDecimal.ONE);
    long unknownCurrency =
        lines.insertParsed(statementId, line, new BigDecimal("-55.00"), "QQQ", BigDecimal.ONE);
    long plain = lines.insertParsed(statementId, line, null, null, null);

    assertThat(lines.findOriginalCharge(foreign))
        .contains(new OriginalCharge(new BigDecimal("-55.0000"), "USD"));
    assertThat(lines.findOriginalCharge(unknownCurrency)).isEmpty();
    assertThat(lines.findOriginalCharge(plain)).isEmpty();
  }

  @Test
  void negateAmountsFlipsAmountAndForeignAmountOfEveryLineOfThatStatementOnly() {
    StatementLine line =
        new StatementLine(
            null,
            0,
            LocalDate.of(2026, 5, 9),
            null,
            new BigDecimal("-50.00"),
            "ShopBbb",
            "d",
            "c",
            "raw",
            null);
    long foreign =
        lines.insertParsed(statementId, line, new BigDecimal("55.00"), "USD", BigDecimal.ONE);
    long plain = lines.insertParsed(statementId, line, null, null, null);

    assertThat(lines.negateAmounts(statementId)).isEqualTo(2);

    List<StatementLine> read = lines.findByStatement(statementId);
    assertThat(read).extracting(StatementLine::amount).allMatch(a -> a.signum() > 0);
    assertThat(
            jdbcClient
                .sql("select original_amount from statement_line where statement_line_id = :id")
                .param("id", foreign)
                .query(BigDecimal.class)
                .single())
        .isEqualByComparingTo("-55.00");
    assertThat(read).extracting(StatementLine::statementLineId).contains(foreign, plain);
  }

  private String originalCurrency(long lineId) {
    return jdbcClient
        .sql("select original_currency_code from statement_line where statement_line_id = :id")
        .param("id", lineId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Test
  void markReseededReplacesHeaderAndRawBodyButKeepsTheBilledUsage() {
    statements.claimForParse(statementId);
    statements.markProcessed(
        statementId,
        LocalDate.of(2026, 5, 1),
        LocalDate.of(2026, 5, 31),
        BigDecimal.TEN,
        BigDecimal.ONE,
        USAGE,
        "old raw");

    int updated =
        statements.markReseeded(
            statementId,
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            BigDecimal.ONE,
            BigDecimal.TWO,
            "new raw");

    assertThat(updated).isEqualTo(1);
    assertThat(statements.findParseRaw(statementId)).contains("new raw");
    Statement read = statements.findById(statementId).orElseThrow();
    assertThat(read.state()).isEqualTo("processed");
    assertThat(read.periodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(read.closingBalance()).isEqualByComparingTo("2");
    Integer tokensIn =
        jdbcClient
            .sql("select tokens_in from statement where statement_id = :id")
            .param("id", statementId)
            .query(Integer.class)
            .single();
    assertThat(tokensIn).isEqualTo(1000);
  }

  @Test
  void markReseededTouchesNothingWhileTheStatementIsStillNew() {
    int updated = statements.markReseeded(statementId, null, null, null, null, "raw");

    assertThat(updated).isZero();
    assertThat(statements.findParseRaw(statementId)).isEmpty();
  }

  @Test
  void findParseUsageReadsTheBilledUsageBackAndIsEmptyBeforeAnyParse() {
    assertThat(statements.findParseUsage(statementId)).isEmpty();
    statements.markProcessed(
        statementId,
        LocalDate.of(2026, 5, 1),
        LocalDate.of(2026, 5, 31),
        BigDecimal.TEN,
        BigDecimal.ONE,
        USAGE,
        "raw");

    ParseUsage read = statements.findParseUsage(statementId).orElseThrow();

    assertThat(read.tokensIn()).isEqualTo(1000);
    assertThat(read.tokensCacheRead()).isEqualTo(40);
    assertThat(read.cost()).isEqualByComparingTo("0.0123");
  }
}
