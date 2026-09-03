package volkovandr.hauptbuch.importer.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL transfer mirror matching within staging (import.md §6.1; plan e1). A transfer appears
 * <strong>twice</strong> — once in each account's export — and the second sighting must be
 * recognised and skipped so the commit (f2) books it once. Matching is on the <strong>posting
 * pair</strong>: same date, the two mapped account ids crossing (this leg's file account is the
 * other leg's named account and vice versa), and equal-and-opposite amount. It is never a
 * transaction-level signature, because a Money split line can itself be a transfer whose mirror
 * arrives as an ordinary unsplit transaction (§6.1).
 *
 * <p>Matching runs entirely inside staging, against rows the importer produced, so it can never
 * swallow a transaction the owner entered by hand — that risk is handled once, explicitly, by the
 * commit-time duplicate scan (§9; plan f1).
 *
 * <p>SQL-resident logic (a scan over {@code import_posting} → {@code import_transaction} → {@code
 * import_file} with a windowed leg count, then a self-join through {@code import_account} on both
 * sides and a window function that pairs identical same-day transfers 1:1), covered in the {@code
 * sqlLogicTest} tier (CLAUDE.md §6).
 */
@Repository
public class ImportMirrorRepository {

  private static final String SESSION_ID = "sessionId";

  /**
   * The matched mirror pairs of a session — <em>symmetric</em>: {@code (pos_id, mirror_id)} appears
   * alongside its reverse. {@code legs} / {@code mirror_legs} are each side's non-funding-leg count
   * (a windowed count over the transaction's postings), so the marking step can tell a simple
   * transfer (exactly one non-funding leg — excludable whole) from a split (its transfer leg is a
   * mirror, but the transaction still books its other legs). A pair where <em>both</em> sides are
   * splits is dropped: neither can be excluded, and that residual is the e4 issues list's problem,
   * not e1's.
   *
   * <p>Pairing is 1:1 via {@code row_number()} over the directed transfer shape {@code
   * (file_account_id, named_account_id, date, amount)}: the k-th sighting of a repeated same-day
   * transfer matches the k-th sighting of its mirror shape, so two identical transfers on one day
   * produce two pairs rather than one leg swallowing three.
   */
  private static final String MATCHED_PAIRS =
      """
      with scoped as (
        select p.import_posting_id     as pos_id,
               p.import_transaction_id as txn_id,
               p.amount                as amount,
               p.money_account_name    as money_account_name,
               p.funding               as funding,
               t.date                  as txn_date,
               t.state                 as txn_state,
               f.import_session_id     as session_id,
               f.money_account_name    as file_money_account_name,
               count(*) filter (where not p.funding)
                 over (partition by p.import_transaction_id) as non_funding_legs
          from import_posting p
          join import_transaction t on t.import_transaction_id = p.import_transaction_id
          join import_file f on f.import_file_id = t.import_file_id
         where f.import_session_id = :sessionId
      ),
      transfer_leg as (
        select s.pos_id,
               s.txn_id,
               s.amount,
               s.txn_date,
               s.non_funding_legs,
               fa.account_id as file_account_id,
               la.account_id as named_account_id
          from scoped s
          join import_account fa on fa.import_session_id = s.session_id
                               and fa.money_account_name = s.file_money_account_name
          join import_account la on la.import_session_id = s.session_id
                               and la.money_account_name = s.money_account_name
         where s.money_account_name is not null
           and not s.funding
           and s.txn_state = 'ready'
           and fa.account_id is not null
           and la.account_id is not null
           and fa.account_id <> la.account_id
      ),
      ranked as (
        select tl.*,
               row_number() over (
                 partition by file_account_id, named_account_id, txn_date, amount
                 order by pos_id
               ) as rn
          from transfer_leg tl
      ),
      matched_pair as (
        select a.pos_id            as pos_id,
               b.pos_id            as mirror_id,
               a.txn_id            as txn_id,
               b.txn_id            as mirror_txn_id,
               a.non_funding_legs  as legs,
               b.non_funding_legs  as mirror_legs
          from ranked a
          join ranked b
            on b.file_account_id  = a.named_account_id
           and b.named_account_id = a.file_account_id
           and b.txn_date         = a.txn_date
           and b.amount           = - a.amount
           and b.rn               = a.rn
         where a.txn_id <> b.txn_id
           and not (a.non_funding_legs > 1 and b.non_funding_legs > 1)
      )
      """;

  private final JdbcClient jdbcClient;

  ImportMirrorRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Re-run mirror matching for a session (import.md §6.1). Clears the session's prior mirror marks,
   * then links every matched posting pair via {@code import_posting.mirror_pair_id} and marks the
   * skippable sighting {@code state = 'mirrored'}: the simple transfer of a simple/split pair, or
   * the later-staged one of two simple transfers. Idempotent and re-runnable — the account map
   * keeps changing under it as the campaign proceeds.
   *
   * @return the number of transactions marked {@code mirrored}
   */
  public int rematch(long importSessionId) {
    clearSessionMirrors(importSessionId);
    return matchAndMark(importSessionId);
  }

  private void clearSessionMirrors(long importSessionId) {
    jdbcClient
        .sql(
            """
            update import_transaction t
               set state = 'ready'
              from import_file f
             where t.import_file_id = f.import_file_id
               and f.import_session_id = :sessionId
               and t.state = 'mirrored'
            """)
        .param(SESSION_ID, importSessionId)
        .update();
    jdbcClient
        .sql(
            """
            update import_posting p
               set mirror_pair_id = null
              from import_transaction t
              join import_file f on f.import_file_id = t.import_file_id
             where p.import_transaction_id = t.import_transaction_id
               and f.import_session_id = :sessionId
               and p.mirror_pair_id is not null
            """)
        .param(SESSION_ID, importSessionId)
        .update();
  }

  /**
   * One statement: the {@code matched_pair} CTE is evaluated once, a data-modifying CTE links every
   * pair's postings (it runs to completion whether or not the outer query reads it), and the outer
   * {@code UPDATE} marks the skippable sighting.
   */
  private int matchAndMark(long importSessionId) {
    return jdbcClient
        .sql(
            MATCHED_PAIRS
                + """
                , linked as (
                  update import_posting ip
                     set mirror_pair_id = mp.mirror_id
                    from matched_pair mp
                   where ip.import_posting_id = mp.pos_id
                  returning ip.import_posting_id
                )
                update import_transaction it
                   set state = 'mirrored'
                 where it.import_transaction_id in (
                   select case
                            when legs = 1 and mirror_legs = 1 then greatest(txn_id, mirror_txn_id)
                            when legs = 1 then txn_id
                            else mirror_txn_id
                          end
                     from matched_pair
                 )
                """)
        .param(SESSION_ID, importSessionId)
        .update();
  }
}
