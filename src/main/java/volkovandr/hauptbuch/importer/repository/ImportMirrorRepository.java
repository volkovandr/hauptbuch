package volkovandr.hauptbuch.importer.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL transfer mirror matching within staging (import.md §6.1; plan e1) and cross-currency
 * parking (§6.2/§6.5; plan e2a). A transfer appears <strong>twice</strong> — once in each account's
 * export — and the second sighting must be recognised and skipped so the commit (f2) books it once.
 *
 * <p>Two matching rules, by whether the transfer crosses a currency boundary (the file's own mapped
 * account currency vs the account its transfer leg names):
 *
 * <ul>
 *   <li><strong>Same currency (e1):</strong> match on the <em>posting pair</em> — same date, the
 *       two mapped account ids crossing, and equal-and-opposite amount. Never a transaction-level
 *       signature, because a Money split line can itself be a transfer whose mirror arrives as an
 *       ordinary unsplit transaction (§6.1).
 *   <li><strong>Cross currency (e2a):</strong> QIF states no far-side amount and {@code
 *       base_amount} is a frozen fact that must never be invented (§6.2), so the transaction
 *       <em>parks</em> ({@code state = 'parked'}, blocking the gate) until the mirror supplies the
 *       real far amount. The pair is matched on the <strong>loosened</strong> signature — date +
 *       the two mapped account ids crossing, near-side amount ignored — and only when that directed
 *       shape is unambiguous 1:1. Anything ambiguous stays parked for a manual match (§6.5; plan
 *       e2b), as does a cross-currency <em>split</em> leg. On a match the surviving sighting's
 *       transfer leg takes the mirror's funding-leg amount as its {@code counter_amount} (V22); the
 *       rate write-back and {@code base_amount} are e3.
 * </ul>
 *
 * <p>Matching runs entirely inside staging, against rows the importer produced, so it can never
 * swallow a transaction the owner entered by hand — that risk is handled once, explicitly, by the
 * commit-time duplicate scan (§9; plan f1).
 *
 * <p>SQL-resident logic (scans over {@code import_posting} → {@code import_transaction} → {@code
 * import_file}, a self-join through {@code import_account} — and, for currency, {@code account} —
 * on both sides, windowed leg counts and a window function that pairs same-day transfers 1:1),
 * covered in the {@code sqlLogicTest} tier (CLAUDE.md §6).
 */
@Repository
public class ImportMirrorRepository {

  private static final String SESSION_ID = "sessionId";

  // A mapped account's currency, in the queries below, is
  // `coalesce(<row>.target_currency_code, <account>.currency_code)`: an existing target takes
  // `account.currency_code`; a new account or person leaf carries the owner's chosen
  // `import_account.target_currency_code` (c1/c2); null only while the row is still unmapped, in
  // which case the transfer's currency question cannot be answered yet and it is left alone. The
  // alias pairs are `fa`/`fac` for the file's own account and `la`/`lac` for the named account.

  /**
   * The matched <strong>same-currency</strong> mirror pairs of a session — <em>symmetric</em>:
   * {@code (pos_id, mirror_id)} appears alongside its reverse. {@code legs} / {@code mirror_legs}
   * are each side's non-funding-leg count (a windowed count over the transaction's postings), so
   * the marking step can tell a simple transfer (exactly one non-funding leg — excludable whole)
   * from a split (its transfer leg is a mirror, but the transaction still books its other legs). A
   * pair where <em>both</em> sides are splits is dropped: neither can be excluded, and that
   * residual is the e4 issues list's problem, not e1's.
   *
   * <p>Pairing is 1:1 via {@code row_number()} over the directed transfer shape {@code
   * (file_account_id, named_account_id, date, amount)}: the k-th sighting of a repeated same-day
   * transfer matches the k-th sighting of its mirror shape, so two identical transfers on one day
   * produce two pairs rather than one leg swallowing three.
   *
   * <p>The {@code transfer_leg} CTE requires both sides to map to the <strong>same</strong>
   * currency: a cross-currency transfer is handled by {@link #RESOLVABLE_CROSS_CURRENCY_PAIRS}, and
   * a coincidental equal-and-opposite amount across two currencies must not be mistaken for a
   * same-currency mirror.
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
          left join account fac on fac.account_id = fa.account_id
          left join account lac on lac.account_id = la.account_id
         where s.money_account_name is not null
           and not s.funding
           and s.txn_state = 'ready'
           and fa.account_id is not null
           and la.account_id is not null
           and fa.account_id <> la.account_id
           and coalesce(fa.target_currency_code, fac.currency_code)
             = coalesce(la.target_currency_code, lac.currency_code)
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

  /**
   * The <strong>resolvable cross-currency</strong> transfer pairs of a session —
   * <em>symmetric</em>, like {@link #MATCHED_PAIRS}. A transfer leg qualifies when its two mapped
   * accounts have <em>different</em> currencies, its transaction is not already a booked mirror,
   * and it is a <em>simple</em> transfer (exactly one non-funding leg): a cross-currency split leg
   * is left parked for the manual match (e2b).
   *
   * <p>Pairing is on the loosened signature — {@code (file_account_id, named_account_id, date)}
   * crossing, near amount ignored — and only when that directed shape holds exactly one sighting on
   * each side ({@code shape_count = 1}). Two same-day transfers between the same accounts cannot be
   * told apart without a rate, so they stay parked.
   *
   * <p>The one amount signal kept is <strong>sign</strong>: the two legs of any one transfer sum to
   * zero, so a real mirror's two transfer legs are opposite-signed (one leg is money into the far
   * account, the other money out of it). Requiring {@code (a.amount > 0) <> (b.amount > 0)} rejects
   * a coincidental pairing of two <em>independent</em> opposite-direction transfers that happen to
   * cross the same accounts on the same day while both their real mirrors are still unstaged.
   *
   * <p>{@code mirror_funding_amount} is the mirror sighting's funding-leg amount — the far side's
   * own file is authoritative for its own currency, and its funding leg carries that native total
   * with the sign the surviving transfer leg needs.
   */
  private static final String RESOLVABLE_CROSS_CURRENCY_PAIRS =
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
                 over (partition by p.import_transaction_id) as non_funding_legs,
               sum(p.amount) filter (where p.funding)
                 over (partition by p.import_transaction_id) as funding_amount
          from import_posting p
          join import_transaction t on t.import_transaction_id = p.import_transaction_id
          join import_file f on f.import_file_id = t.import_file_id
         where f.import_session_id = :sessionId
      ),
      transfer_leg as (
        select s.pos_id,
               s.txn_id,
               s.txn_date,
               s.amount,
               s.funding_amount,
               fa.account_id as file_account_id,
               la.account_id as named_account_id
          from scoped s
          join import_account fa on fa.import_session_id = s.session_id
                               and fa.money_account_name = s.file_money_account_name
          join import_account la on la.import_session_id = s.session_id
                               and la.money_account_name = s.money_account_name
          left join account fac on fac.account_id = fa.account_id
          left join account lac on lac.account_id = la.account_id
         where s.money_account_name is not null
           and not s.funding
           and s.txn_state in ('ready', 'parked')
           and s.non_funding_legs = 1
           and fa.account_id is not null
           and la.account_id is not null
           and coalesce(fa.target_currency_code, fac.currency_code) is not null
           and coalesce(la.target_currency_code, lac.currency_code) is not null
           and coalesce(fa.target_currency_code, fac.currency_code)
             <> coalesce(la.target_currency_code, lac.currency_code)
      ),
      unique_leg as (
        select tl.*,
               count(*) over (
                 partition by file_account_id, named_account_id, txn_date
               ) as shape_count
          from transfer_leg tl
      ),
      matched_pair as (
        select a.pos_id          as pos_id,
               b.pos_id          as mirror_id,
               a.txn_id          as txn_id,
               b.txn_id          as mirror_txn_id,
               b.funding_amount  as mirror_funding_amount
          from unique_leg a
          join unique_leg b
            on b.file_account_id  = a.named_account_id
           and b.named_account_id = a.file_account_id
           and b.txn_date         = a.txn_date
         where a.txn_id <> b.txn_id
           and a.shape_count = 1
           and b.shape_count = 1
           and (a.amount > 0) <> (b.amount > 0)
      )
      """;

  /**
   * Every cross-currency transaction of a session, and whether it has been resolved (its transfer
   * leg carries both a mirror link and a far amount). Drives the re-park step: an unresolved
   * cross-currency transfer parks, and a transaction that is no longer cross-currency (the map
   * changed under it) un-parks.
   */
  private static final String CROSS_CURRENCY_TRANSACTIONS =
      """
      cross_currency_txn as (
        select distinct t.import_transaction_id as txn_id
          from import_posting p
          join import_transaction t on t.import_transaction_id = p.import_transaction_id
          join import_file f on f.import_file_id = t.import_file_id
          join import_account fa on fa.import_session_id = f.import_session_id
                               and fa.money_account_name = f.money_account_name
          join import_account la on la.import_session_id = f.import_session_id
                               and la.money_account_name = p.money_account_name
          left join account fac on fac.account_id = fa.account_id
          left join account lac on lac.account_id = la.account_id
         where f.import_session_id = :sessionId
           and not p.funding
           and p.money_account_name is not null
           and coalesce(fa.target_currency_code, fac.currency_code) is not null
           and coalesce(la.target_currency_code, lac.currency_code) is not null
           and coalesce(fa.target_currency_code, fac.currency_code)
             <> coalesce(la.target_currency_code, lac.currency_code)
      ),
      resolved_txn as (
        select distinct p.import_transaction_id as txn_id
          from import_posting p
          join import_transaction t on t.import_transaction_id = p.import_transaction_id
          join import_file f on f.import_file_id = t.import_file_id
         where f.import_session_id = :sessionId
           and p.mirror_pair_id is not null
           and p.counter_amount is not null
      )
      """;

  private final JdbcClient jdbcClient;

  ImportMirrorRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Re-run mirror matching and cross-currency parking for a session (import.md §6.1/§6.2). Clears
   * the session's prior mirror marks and far amounts, matches same-currency pairs (links the
   * posting pair, marks the skippable sighting {@code mirrored}), resolves the unambiguous
   * cross-currency pairs the same way, and parks every cross-currency transfer that is still
   * unresolved (un-parking any that stopped being cross-currency). Idempotent and re-runnable — the
   * account map keeps changing under it as the campaign proceeds.
   *
   * @return the number of transactions marked {@code mirrored} (same-currency + cross-currency)
   */
  public int rematch(long importSessionId) {
    clearSessionMirrors(importSessionId);
    int sameCurrency = matchAndMark(importSessionId);
    int crossCurrency = matchAndResolveCrossCurrency(importSessionId);
    reparkUnresolvedCrossCurrencyTransfers(importSessionId);
    return sameCurrency + crossCurrency;
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
               set mirror_pair_id = null,
                   counter_amount = null
              from import_transaction t
              join import_file f on f.import_file_id = t.import_file_id
             where p.import_transaction_id = t.import_transaction_id
               and f.import_session_id = :sessionId
               and (p.mirror_pair_id is not null or p.counter_amount is not null)
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

  /**
   * The cross-currency counterpart of {@link #matchAndMark}. A data-modifying CTE links each pair's
   * transfer legs and stamps the {@code counter_amount}; a second promotes the surviving sighting
   * out of {@code parked}; the outer {@code UPDATE} marks the later-staged sighting {@code
   * mirrored}. The two sightings are the {@code least} / {@code greatest} transaction id of the
   * pair, so the disjoint-row rule for multiple data-modifying subqueries holds.
   *
   * @return the number of transactions marked {@code mirrored}
   */
  private int matchAndResolveCrossCurrency(long importSessionId) {
    return jdbcClient
        .sql(
            RESOLVABLE_CROSS_CURRENCY_PAIRS
                + """
                , linked as (
                  update import_posting ip
                     set mirror_pair_id = mp.mirror_id,
                         counter_amount = mp.mirror_funding_amount
                    from matched_pair mp
                   where ip.import_posting_id = mp.pos_id
                  returning ip.import_posting_id
                ),
                survivors as (
                  update import_transaction it
                     set state = 'ready'
                    from matched_pair mp
                   where it.import_transaction_id = least(mp.txn_id, mp.mirror_txn_id)
                     and it.state = 'parked'
                  returning it.import_transaction_id
                )
                update import_transaction it
                   set state = 'mirrored'
                 where it.import_transaction_id in (
                   select greatest(txn_id, mirror_txn_id) from matched_pair
                 )
                """)
        .param(SESSION_ID, importSessionId)
        .update();
  }

  /**
   * Park every cross-currency transfer that has not been resolved, and un-park any transaction that
   * is no longer cross-currency. The surviving sighting of a resolved pair stays {@code ready} (it
   * is in {@code resolved_txn}); the {@code mirrored} sighting is untouched (outside the {@code
   * ('ready', 'parked')} guard).
   */
  private void reparkUnresolvedCrossCurrencyTransfers(long importSessionId) {
    jdbcClient
        .sql(
            "with "
                + CROSS_CURRENCY_TRANSACTIONS
                + """
                , decision as (
                  select t.import_transaction_id as txn_id,
                         case
                           when cc.txn_id is not null and r.txn_id is null then 'parked'
                           else 'ready'
                         end as desired
                    from import_transaction t
                    join import_file f on f.import_file_id = t.import_file_id
                    left join cross_currency_txn cc on cc.txn_id = t.import_transaction_id
                    left join resolved_txn r on r.txn_id = t.import_transaction_id
                   where f.import_session_id = :sessionId
                     and t.state in ('ready', 'parked')
                )
                update import_transaction it
                   set state = d.desired
                  from decision d
                 where it.import_transaction_id = d.txn_id
                   and it.state <> d.desired
                """)
        .param(SESSION_ID, importSessionId)
        .update();
  }
}
