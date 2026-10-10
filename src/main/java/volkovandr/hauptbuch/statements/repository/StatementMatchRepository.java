package volkovandr.hauptbuch.statements.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.statements.StatementCandidate;
import volkovandr.hauptbuch.statements.StatementExtra;
import volkovandr.hauptbuch.statements.StatementMatch;
import volkovandr.hauptbuch.statements.StatementOfLeg;

/**
 * The matcher's queries (statements.md §4): which live ledger legs are candidates for each line,
 * which legs are already matched, and which legs of the period the statement does not account for.
 * The logic lives in the SQL, so these are exercised in the SQL-logic tier (CLAUDE.md §6).
 */
@Repository
public class StatementMatchRepository {

  private static final String STATEMENT_ID = "statementId";
  private static final String LINE_ID = "lineId";
  private static final String POSTING_ID = "postingId";

  private final JdbcClient jdbcClient;

  StatementMatchRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Window candidates for every unmatched, readable line of the statement (statements.md §4.1–4.3).
   * A leg is a candidate when its transaction is live and dated within the profile's window around
   * the line's booking date, and it is either on the statement's account (any amount, when the
   * payee is similar) or — wrong-account tier — on another open real asset or liability account of
   * the same currency, not {@code reconciled}, with an equal amount and a similar payee. Legs
   * already matched to a line of this statement are left out; legs matched on another statement are
   * returned and flagged. A leg the operator excluded from the line ("a different transaction") is
   * left out.
   */
  public List<StatementCandidate> findCandidates(long statementId) {
    return jdbcClient
        .sql(
            """
            with scored as (
              select l.statement_line_id, l.amount as line_amount,
                     s.account_id as statement_account_id,
                     lg.posting_id, lg.transaction_id, lg.account_id, a.name as account_name,
                     a.type as account_type, a.person_leaf, a.deleted_at as account_deleted_at,
                     a.currency_code as account_currency, sa.currency_code as statement_currency,
                     lg.amount, t.date as transaction_date, py.name as payee_name,
                     coalesce(py.name <> '' and position(lower(py.name) in
                       lower(coalesce(l.counterparty, '') || ' ' || coalesce(l.description, '')))
                       > 0, false) as payee_similar,
                     lg.reconciliation,
                     exists (select 1 from statement_match m
                             where m.posting_id = lg.posting_id
                               and m.statement_id <> s.statement_id) as matched_elsewhere,
                     abs(t.date - l.booking_date) as day_distance
              from statement_line l
              join statement s on s.statement_id = l.statement_id
              join statement_profile sp on sp.statement_profile_id = s.statement_profile_id
              join account sa on sa.account_id = s.account_id
              join transaction t
                on t.deleted_at is null
               and t.date between l.booking_date - sp.window_days_before
                              and l.booking_date + sp.window_days_after
              join lateral (
                select p.transaction_id, p.account_id, sum(p.amount) as amount,
                       min(p.posting_id) as posting_id,
                       case when bool_and(p.reconciliation = 'reconciled') then 'reconciled'
                            when bool_or(p.reconciliation = 'cleared') then 'cleared'
                            else 'unreconciled' end as reconciliation
                from posting p
                where p.transaction_id = t.transaction_id
                  and p.account_id in (select r.account_id from account r
                                       where r.type in ('asset', 'liability') and not r.person_leaf)
                group by p.transaction_id, p.account_id
              ) lg on true
              join account a on a.account_id = lg.account_id
              left join payee py on py.payee_id = t.payee_id
              where l.statement_id = :statementId
                and l.problem is null
                and l.booking_date is not null
                and l.amount is not null
                and not exists (select 1 from statement_match m
                                where m.statement_line_id = l.statement_line_id)
                and not exists (select 1 from statement_match m
                                where m.statement_id = s.statement_id
                                  and m.posting_id = lg.posting_id)
                and not exists (select 1 from statement_line_exclusion x
                                where x.statement_line_id = l.statement_line_id
                                  and x.posting_id = lg.posting_id)
            )
            select statement_line_id, posting_id, transaction_id, account_id, account_name, amount,
                   transaction_date, payee_name, payee_similar, reconciliation, matched_elsewhere,
                   day_distance
            from scored
            where (account_id = statement_account_id and (amount = line_amount or payee_similar))
               or (account_id <> statement_account_id
                   and account_type in ('asset', 'liability')
                   and not person_leaf
                   and account_deleted_at is null
                   and account_currency = statement_currency
                   and reconciliation <> 'reconciled'
                   and amount = line_amount
                   and payee_similar)
            order by statement_line_id, day_distance, posting_id
            """)
        .param(STATEMENT_ID, statementId)
        .query(StatementCandidate.class)
        .list();
  }

  /** The statement's confirmed matches, with the matched leg's details. */
  public List<StatementMatch> findMatches(long statementId) {
    return jdbcClient
        .sql(
            """
            select m.statement_line_id, m.posting_id, p.transaction_id,
                   t.date as transaction_date, py.name as payee_name, p.amount, p.reconciliation
            from statement_match m
            join posting p on p.posting_id = m.posting_id
            join transaction t on t.transaction_id = p.transaction_id
            left join payee py on py.payee_id = t.payee_id
            where m.statement_id = :statementId
            order by m.statement_line_id
            """)
        .param(STATEMENT_ID, statementId)
        .query(StatementMatch.class)
        .list();
  }

  /**
   * Legs on the statement's account, in live transactions dated in its period, that are neither
   * matched to this statement nor {@code reconciled} (statements.md §6.3).
   */
  public List<StatementExtra> findExtras(long statementId) {
    return jdbcClient
        .sql(
            """
            select p.posting_id, p.transaction_id, t.date as transaction_date,
                   py.name as payee_name, t.note, p.amount
            from statement s
            join posting p on p.account_id = s.account_id
            join transaction t on t.transaction_id = p.transaction_id
            left join payee py on py.payee_id = t.payee_id
            where s.statement_id = :statementId
              and t.deleted_at is null
              and t.date between s.period_start and s.period_end
              and p.reconciliation <> 'reconciled'
              and not exists (select 1 from statement_match m
                              where m.statement_id = s.statement_id
                                and m.posting_id = p.posting_id)
            order by t.date, p.posting_id
            """)
        .param(STATEMENT_ID, statementId)
        .query(StatementExtra.class)
        .list();
  }

  /**
   * The account's ledger balance through a date: the sum of its legs in live transactions dated on
   * or before it (statements.md §6.2), zero when there are none.
   */
  public BigDecimal ledgerBalance(long accountId, LocalDate through) {
    return jdbcClient
        .sql(
            """
            select coalesce(sum(p.amount), 0)
            from posting p
            join transaction t on t.transaction_id = p.transaction_id
            where p.account_id = :accountId
              and t.deleted_at is null
              and t.date <= :through
            """)
        .param("accountId", accountId)
        .param("through", through)
        .query(BigDecimal.class)
        .single();
  }

  /** Record the confirmed match of one line to one posting. */
  public void insertMatch(long statementId, long statementLineId, long postingId) {
    jdbcClient
        .sql(
            """
            insert into statement_match (statement_line_id, statement_id, posting_id)
            values (:lineId, :statementId, :postingId)
            """)
        .param(LINE_ID, statementLineId)
        .param(STATEMENT_ID, statementId)
        .param(POSTING_ID, postingId)
        .update();
  }

  /** Remove every match on these postings, on any statement — a match needs a reconciled leg. */
  public void deleteMatchesOnPostings(Collection<Long> postingIds) {
    if (postingIds.isEmpty()) {
      return;
    }
    jdbcClient
        .sql("delete from statement_match where posting_id in (:postingIds)")
        .param("postingIds", postingIds)
        .update();
  }

  /** Remove the matches of these lines of the statement and return the postings they were on. */
  public List<Long> deleteMatchesOfLines(long statementId, Collection<Long> lineIds) {
    if (lineIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            delete from statement_match
            where statement_id = :statementId and statement_line_id in (:lineIds)
            returning posting_id
            """)
        .param(STATEMENT_ID, statementId)
        .param("lineIds", lineIds)
        .query(Long.class)
        .list();
  }

  /** Remove all of a statement's matches and return the postings that were matched. */
  public List<Long> deleteMatchesOfStatement(long statementId) {
    return jdbcClient
        .sql("delete from statement_match where statement_id = :statementId returning posting_id")
        .param(STATEMENT_ID, statementId)
        .query(Long.class)
        .list();
  }

  /** Of these postings, the ones no statement matches any more. */
  public List<Long> postingsWithoutMatch(Collection<Long> postingIds) {
    if (postingIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            select p.posting_id from posting p
            where p.posting_id in (:postingIds)
              and not exists (select 1 from statement_match m where m.posting_id = p.posting_id)
            order by p.posting_id
            """)
        .param("postingIds", postingIds)
        .query(Long.class)
        .list();
  }

  /** The operator's decision that this posting is not the line's movement (statements.md §4.4). */
  public void insertExclusion(long statementLineId, long postingId) {
    jdbcClient
        .sql(
            """
            insert into statement_line_exclusion (statement_line_id, posting_id)
            values (:lineId, :postingId)
            on conflict (statement_line_id, posting_id) do nothing
            """)
        .param(LINE_ID, statementLineId)
        .param(POSTING_ID, postingId)
        .update();
  }

  /** The statements that match a leg of the transaction, one row per matched leg. */
  public List<StatementOfLeg> findStatementsOfTransaction(long transactionId) {
    return jdbcClient
        .sql(
            """
            select a.name as account_name, s.period_start, s.original_filename
            from statement_match m
            join posting p on p.posting_id = m.posting_id
            join account a on a.account_id = p.account_id
            join statement s on s.statement_id = m.statement_id
            where p.transaction_id = :transactionId
              and s.deleted_at is null
            order by s.period_start nulls last, m.statement_match_id
            """)
        .param("transactionId", transactionId)
        .query(StatementOfLeg.class)
        .list();
  }
}
