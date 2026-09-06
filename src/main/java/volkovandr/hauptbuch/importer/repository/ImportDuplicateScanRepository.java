package volkovandr.hauptbuch.importer.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportDuplicateMatch;
import volkovandr.hauptbuch.importer.ImportDuplicateScanRow;

/**
 * Native-SQL access to the commit-time ledger duplicate scan (import.md §9; plan f1) — the fourth
 * commit-gate condition, alongside e4's {@code ImportIssues}.
 *
 * <p>{@link #rescan} is the whole of the scan: it upserts the one {@code import_duplicate_scan} row
 * for the campaign, re-stamps {@code ran_at}, and reconciles {@code import_duplicate_match} against
 * a freshly-computed set of overlaps. The detection is: for every staged transaction that will book
 * ({@code state = 'ready'}, not an opening balance — those are the c3 reconciliation's, §5.1), the
 * live ledger transactions that share its <strong>date</strong>, its <strong>funding
 * account</strong> and that leg's <strong>amount</strong>, and at least one non-funding leg whose
 * <strong>account</strong> and <strong>amount</strong> the ledger also posted. The non-funding
 * account is the mapped category node itself or the currency leaf beneath it the ledger already
 * posted to ({@code CurrencyLeafService} routes to that leaf at the f2 commit; the scan matches
 * whichever the ledger used). The non-funding amount is that leg's native amount — {@code
 * counter_amount} for a resolved cross-currency transfer leg (whose {@code amount} is only the
 * funding-currency view; §6), {@code amount} otherwise — matched against the ledger's native {@code
 * posting.amount}. A staged transaction can overlap more than one ledger transaction, so each pair
 * is its own {@code import_duplicate_match} row.
 *
 * <p>The reconcile (Q-IMP-5, settled at plan f1 — a re-runnable snapshot, no ledger lock):
 *
 * <ul>
 *   <li>a pair no longer overlapping is <strong>dropped</strong> only when it is safe to — it was
 *       never adjudicated, or its ledger transaction is gone, or the ledger transaction has
 *       <em>not</em> changed since the owner decided ({@code updated_at <= ledger_seen_at});
 *   <li>a pair the owner adjudicated whose ledger transaction has since been touched ({@code
 *       updated_at} now past the {@code ledger_seen_at} captured at {@link #adjudicate}) is
 *       <strong>re-raised</strong> to {@code pending} — whether or not it still overlaps — so a
 *       stale decision is never trusted and never silently lost;
 *   <li>a newly-overlapping pair is inserted {@code pending}.
 * </ul>
 *
 * <p>SQL-resident logic (the detection query spans {@code import_transaction} → {@code import_file}
 * → {@code import_posting} → {@code import_account}/{@code import_category} → {@code transaction} →
 * {@code posting} → {@code account}, with {@code exists} sub-joins and a currency-leaf parent test;
 * the reconcile is data-modifying CTEs) — covered in the {@code sqlLogicTest} tier (CLAUDE.md §6).
 */
@Repository
public class ImportDuplicateScanRepository {

  private static final String SESSION_ID = "sessionId";
  private static final String SCAN_ID = "scanId";
  private static final String MATCH_ID = "matchId";

  /**
   * The staged ↔ live overlaps for a session — the {@code fresh} set {@link #rescan} reconciles
   * against. Not exposed on its own: the scan's only entry point is {@link #rescan}, so the query
   * under test in the SQL-logic tier is exactly the one production runs (CLAUDE.md §6).
   */
  private static final String MATCH_QUERY =
      """
      with staged as (
        select t.import_transaction_id as import_transaction_id,
               t.date                  as txn_date,
               fund_acc.account_id     as funding_account_id,
               fp.amount               as funding_amount
          from import_transaction t
          join import_file f on f.import_file_id = t.import_file_id
          join import_posting fp on fp.import_transaction_id = t.import_transaction_id
                               and fp.funding
          join import_account fund_acc on fund_acc.import_session_id = f.import_session_id
                                     and fund_acc.money_account_name = fp.money_account_name
         where f.import_session_id = :sessionId
           and t.state = 'ready'
           and not t.opening_balance
           and fund_acc.account_id is not null
      ),
      staged_leg as (
        select p.import_transaction_id               as import_transaction_id,
               coalesce(ic.account_id, ia.account_id) as leg_account_id,
               coalesce(p.counter_amount, p.amount)   as leg_amount
          from import_posting p
          join import_transaction t on t.import_transaction_id = p.import_transaction_id
          join import_file f on f.import_file_id = t.import_file_id
          left join import_category ic on ic.import_session_id = f.import_session_id
                                      and ic.money_path = p.money_category_path
          left join import_account ia on ia.import_session_id = f.import_session_id
                                     and ia.money_account_name = p.money_account_name
         where f.import_session_id = :sessionId
           and not p.funding
      )
      select distinct s.import_transaction_id as import_transaction_id,
                      lt.transaction_id       as transaction_id
        from staged s
        join transaction lt on lt.date = s.txn_date and lt.deleted_at is null
        join posting lfund on lfund.transaction_id = lt.transaction_id
                          and lfund.account_id = s.funding_account_id
                          and lfund.amount = s.funding_amount
       where exists (
         select 1
           from staged_leg sl
           join posting lleg on lleg.transaction_id = lt.transaction_id
                            and lleg.amount = sl.leg_amount
           join account la on la.account_id = lleg.account_id
          where sl.import_transaction_id = s.import_transaction_id
            and sl.leg_account_id is not null
            and (la.account_id = sl.leg_account_id or la.parent_id = sl.leg_account_id)
       )
      """;

  private final JdbcClient jdbcClient;

  ImportDuplicateScanRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** The campaign's current scan snapshot, or empty when the scan has never been run. */
  public Optional<ImportDuplicateScanRow> findScan(long importSessionId) {
    return jdbcClient
        .sql(
            "select import_duplicate_scan_id, ran_at from import_duplicate_scan"
                + " where import_session_id = :sessionId")
        .param(SESSION_ID, importSessionId)
        .query(ImportDuplicateScanRow.class)
        .optional();
  }

  /**
   * Run (or re-run) the scan for a session — upsert the snapshot, re-stamp {@code ran_at}, and
   * reconcile {@code import_duplicate_match} against a fresh detection (see the class javadoc).
   *
   * @return the number of matches on the snapshot after the reconcile
   */
  public int rescan(long importSessionId) {
    long scanId =
        jdbcClient
            .sql(
                """
                insert into import_duplicate_scan (import_session_id) values (:sessionId)
                on conflict (import_session_id) do update set ran_at = now()
                returning import_duplicate_scan_id
                """)
            .param(SESSION_ID, importSessionId)
            .query(Long.class)
            .single();

    // Re-raise first: an adjudication made against a ledger transaction that has changed since goes
    // back to `pending` whether or not the pair still overlaps — a stale decision is never trusted.
    jdbcClient
        .sql(
            """
            update import_duplicate_match m
               set adjudication = 'pending'
              from transaction lt
             where m.import_duplicate_scan_id = :scanId
               and lt.transaction_id = m.transaction_id
               and lt.deleted_at is null
               and m.adjudication <> 'pending'
               and (m.ledger_seen_at is null or lt.updated_at > m.ledger_seen_at)
            """)
        .param(SCAN_ID, scanId)
        .update();

    // Then drop / add. A no-longer-overlapping pair is removed only when it is safe: never
    // adjudicated (and not a re-raised orphan — those keep a non-null ledger_seen_at), or its
    // ledger transaction is gone, or that transaction has not moved since the decision.
    jdbcClient
        .sql(
            "with fresh as ("
                + MATCH_QUERY
                + """
                ),
                deleted as (
                  delete from import_duplicate_match m
                   where m.import_duplicate_scan_id = :scanId
                     and not exists (
                       select 1 from fresh fr
                        where fr.import_transaction_id = m.import_transaction_id
                          and fr.transaction_id = m.transaction_id)
                     and (
                       (m.adjudication = 'pending' and m.ledger_seen_at is null)
                       or not exists (
                         select 1 from transaction lt
                          where lt.transaction_id = m.transaction_id and lt.deleted_at is null)
                       or exists (
                         select 1 from transaction lt
                          where lt.transaction_id = m.transaction_id
                            and lt.updated_at <= m.ledger_seen_at)
                     )
                  returning m.import_duplicate_match_id
                )
                insert into import_duplicate_match
                  (import_duplicate_scan_id, import_transaction_id, transaction_id)
                select :scanId, fr.import_transaction_id, fr.transaction_id
                  from fresh fr
                 where not exists (
                   select 1 from import_duplicate_match m
                    where m.import_duplicate_scan_id = :scanId
                      and m.import_transaction_id = fr.import_transaction_id
                      and m.transaction_id = fr.transaction_id)
                """)
        .param(SESSION_ID, importSessionId)
        .param(SCAN_ID, scanId)
        .update();

    return jdbcClient
        .sql("select count(*) from import_duplicate_match where import_duplicate_scan_id = :scanId")
        .param(SCAN_ID, scanId)
        .query(Integer.class)
        .single();
  }

  /**
   * Discard the campaign's scan snapshot (and its matches, by cascade). A no-op when none exists.
   */
  public void clearScan(long importSessionId) {
    jdbcClient
        .sql("delete from import_duplicate_scan where import_session_id = :sessionId")
        .param(SESSION_ID, importSessionId)
        .update();
  }

  /**
   * The staged transactions the owner adjudicated {@code skip} on the campaign's current scan
   * (import.md §9; plan f2) — the commit does not book these. Empty when the scan has never run.
   * Plain lookup by the {@code (session → scan → match)} chain; a {@code Set} because the commit
   * only needs membership.
   */
  public Set<Long> skippedImportTransactionIds(long importSessionId) {
    return Set.copyOf(
        jdbcClient
            .sql(
                """
                select m.import_transaction_id
                  from import_duplicate_match m
                  join import_duplicate_scan s
                    on s.import_duplicate_scan_id = m.import_duplicate_scan_id
                 where s.import_session_id = :sessionId
                   and m.adjudication = 'skip'
                """)
            .param(SESSION_ID, importSessionId)
            .query(Long.class)
            .list());
  }

  /** The scan's matches joined for display, date then id order. */
  public List<ImportDuplicateMatch> findMatchRows(long importDuplicateScanId) {
    return jdbcClient
        .sql(
            """
            select m.import_duplicate_match_id as import_duplicate_match_id,
                   m.import_transaction_id     as import_transaction_id,
                   m.transaction_id            as transaction_id,
                   it.date                     as date,
                   f.money_account_name        as money_account_name,
                   fp.amount                   as amount,
                   it.payee_text               as staged_payee,
                   pay.name                    as ledger_payee,
                   lt.note                     as ledger_note,
                   m.adjudication              as adjudication
              from import_duplicate_match m
              join import_transaction it on it.import_transaction_id = m.import_transaction_id
              join import_file f on f.import_file_id = it.import_file_id
              join import_posting fp on fp.import_transaction_id = it.import_transaction_id
                                   and fp.funding
              join transaction lt on lt.transaction_id = m.transaction_id
              left join payee pay on pay.payee_id = lt.payee_id
             where m.import_duplicate_scan_id = :scanId
             order by it.date, m.import_duplicate_match_id
            """)
        .param(SCAN_ID, importDuplicateScanId)
        .query(ImportDuplicateMatch.class)
        .list();
  }

  /**
   * Record the owner's decision on one match and capture the ledger transaction's current {@code
   * updated_at} as {@code ledger_seen_at} — the fingerprint the next {@link #rescan} checks for
   * staleness. No-op (returns false) when the match is not on that scan.
   */
  public boolean adjudicate(
      long importDuplicateScanId, long importDuplicateMatchId, String decision) {
    return jdbcClient
            .sql(
                """
                update import_duplicate_match m
                   set adjudication = :decision,
                       ledger_seen_at = (select lt.updated_at from transaction lt
                                          where lt.transaction_id = m.transaction_id)
                 where m.import_duplicate_match_id = :matchId
                   and m.import_duplicate_scan_id = :scanId
                """)
            .param("decision", decision)
            .param(MATCH_ID, importDuplicateMatchId)
            .param(SCAN_ID, importDuplicateScanId)
            .update()
        > 0;
  }

  /**
   * The most recent {@code updated_at} across every ledger transaction — {@code updated_at} is
   * {@code not null default now()} and every edit / void stamps it, so it dominates {@code
   * created_at}. Empty when the ledger has no transactions. The scan is stale when its {@code
   * ran_at} predates this: the owner booked or changed a ledger transaction after the scan ran, and
   * the importer cannot hook that (it happens on another screen), so it is caught here by time.
   */
  public Optional<OffsetDateTime> latestLedgerMutation() {
    return jdbcClient
        .sql("select max(updated_at) from transaction")
        .query(OffsetDateTime.class)
        .optional();
  }
}
