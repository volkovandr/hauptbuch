package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;

/**
 * Integration tier (CLAUDE.md §6): the match actions' writes round-trip against real Postgres — a
 * confirmed match, the removal of matches by posting or by statement, the postings no statement
 * matches any more, and the stored "a different transaction" decision. Each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementMatchRepositoryIntegrationTest {

  @Autowired StatementMatchRepository matches;
  @Autowired JdbcClient jdbcClient;

  private long accountId;
  private long statementId;
  private long otherStatementId;
  private long lineId;
  private long otherLineId;
  private long postingId;
  private long otherPostingId;

  @BeforeEach
  void seed() {
    accountId =
        jdbcClient
            .sql(
                "insert into account (name, type, currency_code) values ('BankAaa-EUR', 'asset',"
                    + " 'EUR') returning account_id")
            .query(Long.class)
            .single();
    long profileId =
        jdbcClient
            .sql(
                "insert into statement_profile (name, format, window_days_before,"
                    + " window_days_after) values ('BankAaa CSV', 'csv', 7, 2)"
                    + " returning statement_profile_id")
            .query(Long.class)
            .single();
    statementId = statement(profileId);
    otherStatementId = statement(profileId);
    lineId = line(statementId);
    otherLineId = line(otherStatementId);
    postingId = posting();
    otherPostingId = posting();
  }

  @Test
  void insertMatchStoresTheLinePostingAndStatement() {
    matches.insertMatch(statementId, lineId, postingId);

    assertThat(
            jdbcClient
                .sql(
                    "select posting_id from statement_match where statement_id = :s"
                        + " and statement_line_id = :l")
                .param("s", statementId)
                .param("l", lineId)
                .query(Long.class)
                .single())
        .isEqualTo(postingId);
  }

  @Test
  void deleteMatchesOfLinesRemovesOnlyThoseLinesOfThatStatement() {
    matches.insertMatch(statementId, lineId, postingId);
    matches.insertMatch(otherStatementId, otherLineId, postingId);

    List<Long> deleted = matches.deleteMatchesOfLines(statementId, List.of(lineId, otherLineId));

    assertThat(deleted).containsExactly(postingId);
    assertThat(matchCount()).isEqualTo(1);
    assertThat(matches.deleteMatchesOfLines(statementId, List.of())).isEmpty();
  }

  @Test
  void deleteMatchesOnPostingsRemovesThemOnEveryStatement() {
    matches.insertMatch(statementId, lineId, postingId);
    matches.insertMatch(otherStatementId, otherLineId, postingId);

    matches.deleteMatchesOnPostings(List.of(postingId));

    assertThat(matchCount()).isZero();
  }

  @Test
  void deleteMatchesOnPostingsOfNothingDeletesNothing() {
    matches.insertMatch(statementId, lineId, postingId);

    matches.deleteMatchesOnPostings(List.of());

    assertThat(matchCount()).isEqualTo(1);
  }

  @Test
  void deleteMatchesOfStatementReturnsItsPostingsAndKeepsOtherStatementsMatches() {
    matches.insertMatch(statementId, lineId, postingId);
    matches.insertMatch(otherStatementId, otherLineId, otherPostingId);

    List<Long> deleted = matches.deleteMatchesOfStatement(statementId);

    assertThat(deleted).containsExactly(postingId);
    assertThat(matchCount()).isEqualTo(1);
  }

  @Test
  void postingsWithoutMatchLeavesOutThoseAnotherStatementStillMatches() {
    matches.insertMatch(otherStatementId, otherLineId, otherPostingId);

    assertThat(matches.postingsWithoutMatch(List.of(postingId, otherPostingId)))
        .containsExactly(postingId);
    assertThat(matches.postingsWithoutMatch(List.of())).isEmpty();
  }

  @Test
  void insertExclusionStoresThePairOnceAndDropsItWithThePosting() {
    matches.insertExclusion(lineId, postingId);
    matches.insertExclusion(lineId, postingId);

    assertThat(exclusionCount()).isEqualTo(1);

    jdbcClient.sql("delete from posting where posting_id = :p").param("p", postingId).update();

    assertThat(exclusionCount()).isZero();
  }

  private long matchCount() {
    return jdbcClient.sql("select count(*) from statement_match").query(Long.class).single();
  }

  private long exclusionCount() {
    return jdbcClient
        .sql("select count(*) from statement_line_exclusion")
        .query(Long.class)
        .single();
  }

  private long statement(long profileId) {
    return jdbcClient
        .sql(
            "insert into statement (statement_profile_id, account_id, state, original_filename,"
                + " file_path) values (:p, :a, 'new', 'a.csv', 'x') returning statement_id")
        .param("p", profileId)
        .param("a", accountId)
        .query(Long.class)
        .single();
  }

  private long line(long forStatement) {
    return jdbcClient
        .sql(
            "insert into statement_line (statement_id, sort_order, raw_text) values (:s, 0, 'r')"
                + " returning statement_line_id")
        .param("s", forStatement)
        .query(Long.class)
        .single();
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
}
