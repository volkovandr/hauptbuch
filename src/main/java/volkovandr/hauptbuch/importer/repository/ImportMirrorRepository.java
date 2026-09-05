package volkovandr.hauptbuch.importer.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportCrossCurrencyPark;
import volkovandr.hauptbuch.importer.ImportCrossCurrencyRateCandidate;

/**
 * Native-SQL transfer mirror matching within staging (import.md §6.1; plan e1), cross-currency
 * parking (§6.2/§6.5; plan e2a) and its manual resolution (§6.4/§6.5; plan e2b). A transfer appears
 * <strong>twice</strong> — once in each account's export — and the second sighting must be
 * recognised and skipped so the commit (f2) books it once.
 *
 * <p>Three matching rules, by whether the transfer crosses a currency boundary (the file's own
 * mapped account currency vs the account its transfer leg names):
 *
 * <ul>
 *   <li><strong>Same currency (e1):</strong> match on the <em>posting pair</em> — same date, the
 *       two mapped account ids crossing, and equal-and-opposite amount. Never a transaction-level
 *       signature, because a Money split line can itself be a transfer whose mirror arrives as an
 *       ordinary unsplit transaction (§6.1).
 *   <li><strong>Cross currency, automatic (e2a):</strong> QIF states no far-side amount and {@code
 *       base_amount} is a frozen fact that must never be invented (§6.2), so the transaction
 *       <em>parks</em> ({@code state = 'parked'}, blocking the gate) until the mirror supplies the
 *       real far amount. The pair is matched on the <strong>loosened</strong> signature — date +
 *       the two mapped account ids crossing, near-side amount ignored — and only when that directed
 *       shape is unambiguous 1:1. Anything ambiguous stays parked for a manual match (§6.5), as
 *       does a cross-currency <em>split</em> leg.
 *   <li><strong>Cross currency, manual (e2b):</strong> {@link #manualMatch} lets the owner pair two
 *       parked legs the automatic pass could not disambiguate, and {@link #closeParkWithFarAmount}
 *       lets the owner type the far amount by hand for a counterparty whose export will never
 *       arrive (§6.4, {@code expect-file} cleared) — there is no second sighting to match against
 *       at all.
 * </ul>
 *
 * <p>On a resolution — automatic or manual — the surviving sighting's transfer leg takes the real
 * far-currency amount as its {@code counter_amount} (V22), same sign as {@code amount}; a matched
 * (as opposed to hand-entered) resolution also links {@code mirror_pair_id} and marks the redundant
 * sighting {@code mirrored}. {@link #resolvedCrossCurrencyRateCandidates} (plan e3) reports every
 * currently-resolved leg's own two real native amounts for the caller ({@code importer}'s service
 * layer) to offer to {@code ledger}'s {@code ExchangeRateService} — writing to {@code
 * exchange_rate} is {@code ledger}'s own table, so this repository never touches it directly
 * (CLAUDE.md §1). {@code posting.base_amount} itself is still not fixed here — that is the commit
 * (f2), which reads {@code amount}/{@code counter_amount} at booking time.
 *
 * <p><strong>A cross-currency resolution is not always re-derivable</strong> — a manual match is by
 * definition a shape the automatic pass cannot disambiguate on its own, and a hand-entered far
 * amount has no second sighting to re-derive from at all. So {@link #rematch}, which the campaign
 * re-runs on every account-map edit (I-5), <em>preserves</em> a cross-currency resolution across an
 * unrelated edit instead of blindly clearing it, and invalidates one only when it is no longer a
 * cross-currency transfer leg under the <strong>current</strong> map (§{@link
 * #invalidateStaleCrossCurrencyResolutions}). A same-currency mirror link (e1) stays fully
 * re-derivable and is cleared and rebuilt every run, as before.
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
  private static final String FILE_ID = "fileId";
  private static final String FILENAME = "filename";
  private static final String POSTING_ID = "postingId";
  private static final String MIRROR_ID = "mirrorId";
  private static final String READY = "ready";
  private static final String MIRRORED = "mirrored";

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
   * accounts have <em>different</em> currencies, its transaction is not already a booked mirror, it
   * is not <strong>already resolved</strong> ({@code counter_amount is null} — excluding an
   * already-resolved sibling keeps it from inflating {@code shape_count} for a still-parked leg
   * that shares its (file, named, date) shape, e.g. after e2b resolves one pair of an ambiguous
   * same-day set), and it is a <em>simple</em> transfer (exactly one non-funding leg): a
   * cross-currency split leg is left parked for the manual match (e2b).
   *
   * <p>Pairing is on the loosened signature — {@code (file_account_id, named_account_id, date)}
   * crossing, near amount ignored — and only when that directed shape holds exactly one sighting on
   * each side ({@code shape_count = 1}). Two same-day transfers between the same accounts cannot be
   * told apart without a rate, so they stay parked.
   *
   * <p>The one amount signal kept is <strong>sign</strong>: the two legs of any one transfer sum to
   * zero, so a real mirror's two transfer legs are opposite-signed (one leg is money into the far
   * account, the other money out of it). Requiring {@code sign(a.amount) * sign(b.amount) = -1}
   * rejects a coincidental pairing of two <em>independent</em> opposite-direction transfers that
   * happen to cross the same accounts on the same day while both their real mirrors are still
   * unstaged — and, unlike a bare {@code (a.amount > 0) <> (b.amount > 0)}, never misreads a
   * degenerate zero-amount leg as "opposite" to a real one ({@code sign(0) = 0}, so the product is
   * {@code 0}, never {@code -1}).
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
               p.counter_amount        as counter_amount,
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
           and s.counter_amount is null
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
           and sign(a.amount) * sign(b.amount) = -1
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
           and p.counter_amount is not null
      )
      """;

  /**
   * The currently-resolved cross-currency postings of a session (a posting with {@code
   * counter_amount} set — set by {@link #matchAndResolveCrossCurrency}, {@link #manualMatch} or
   * {@link #closeParkWithFarAmount}) whose crossing no longer holds under the
   * <strong>current</strong> map: the two mapped accounts' currencies are unknown or now equal. A
   * posting's own crossing fully determines this — its {@code file_account_id}/{@code
   * named_account_id} are always looked up from the same shared {@code import_account} rows its
   * former mirror partner would use, so checking the row on its own is equivalent to checking the
   * pair.
   */
  private static final String STALE_CROSS_CURRENCY_RESOLUTIONS =
      """
      with resolved as (
        select p.import_posting_id as pos_id,
               coalesce(fa.target_currency_code, fac.currency_code) as near_currency,
               coalesce(la.target_currency_code, lac.currency_code) as far_currency
          from import_posting p
          join import_transaction t on t.import_transaction_id = p.import_transaction_id
          join import_file f on f.import_file_id = t.import_file_id
          join import_account fa on fa.import_session_id = f.import_session_id
                               and fa.money_account_name = f.money_account_name
          left join import_account la on la.import_session_id = f.import_session_id
                                     and la.money_account_name = p.money_account_name
          left join account fac on fac.account_id = fa.account_id
          left join account lac on lac.account_id = la.account_id
         where f.import_session_id = :sessionId
           and p.counter_amount is not null
      )
      select pos_id
        from resolved
       where near_currency is null
          or far_currency is null
          or near_currency = far_currency
      """;

  private final JdbcClient jdbcClient;

  ImportMirrorRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Re-run mirror matching and cross-currency parking for a session (import.md §6.1/§6.2/§6.5).
   * Invalidates any cross-currency resolution that is no longer valid under the current map,
   * matches same-currency pairs (links the posting pair, marks the skippable sighting {@code
   * mirrored}), resolves the unambiguous cross-currency pairs the same way, and parks every
   * cross-currency transfer that is still unresolved (un-parking any that stopped being
   * cross-currency). Idempotent and re-runnable — the account map keeps changing under it as the
   * campaign proceeds — and it <strong>preserves</strong> a manual match or a hand-entered far
   * amount (e2b) rather than clearing and failing to re-derive it.
   *
   * @return the number of transactions marked {@code mirrored} (same-currency + cross-currency)
   */
  public int rematch(long importSessionId) {
    invalidateStaleCrossCurrencyResolutions(importSessionId);
    resetMirroredState(importSessionId);
    clearSameCurrencyMirrorLinks(importSessionId);
    int sameCurrency = matchAndMark(importSessionId);
    int crossCurrency = matchAndResolveCrossCurrency(importSessionId);
    reparkUnresolvedCrossCurrencyTransfers(importSessionId);
    return sameCurrency + crossCurrency;
  }

  /**
   * Clear {@code mirror_pair_id}/{@code counter_amount} on every cross-currency-resolved posting
   * (automatic, manual or hand-entered) whose crossing no longer holds under the current map — see
   * {@link #STALE_CROSS_CURRENCY_RESOLUTIONS}. A still-valid resolution is left untouched, which is
   * what lets a manual match or a hand-entered far amount survive a rematch triggered by an
   * unrelated account-map edit.
   */
  private void invalidateStaleCrossCurrencyResolutions(long importSessionId) {
    jdbcClient
        .sql(
            """
            update import_posting p
               set mirror_pair_id = null, counter_amount = null
             where p.import_posting_id in (
            """
                + STALE_CROSS_CURRENCY_RESOLUTIONS
                + ")")
        .param(SESSION_ID, importSessionId)
        .update();
  }

  /**
   * Reset every {@code mirrored} transaction of a session back to {@code ready}, except one still
   * holding a valid cross-currency resolution (a posting with {@code counter_amount} not null) —
   * that transaction is the excluded sighting of a preserved cross-currency pair and must stay
   * {@code mirrored}. A same-currency mirror link never carries {@code counter_amount}, so it is
   * always reset and rebuilt fresh by {@link #matchAndMark}.
   */
  private void resetMirroredState(long importSessionId) {
    jdbcClient
        .sql(
            """
            update import_transaction t
               set state = 'ready'
              from import_file f
             where t.import_file_id = f.import_file_id
               and f.import_session_id = :sessionId
               and t.state = 'mirrored'
               and not exists (
                 select 1 from import_posting p
                  where p.import_transaction_id = t.import_transaction_id
                    and p.counter_amount is not null
               )
            """)
        .param(SESSION_ID, importSessionId)
        .update();
  }

  /**
   * Clear the {@code mirror_pair_id} of every same-currency mirror link (never carries {@code
   * counter_amount}, unlike a cross-currency resolution) — always fully re-derivable, so it is
   * unconditionally cleared and rebuilt by {@link #matchAndMark} every run.
   */
  private void clearSameCurrencyMirrorLinks(long importSessionId) {
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
               and p.counter_amount is null
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

  /**
   * The still-parked cross-currency transfer legs of a session — the review's cross-currency panel
   * (import.md §9, §6.5; plan e2b). Each row is a candidate for {@link #manualMatch} against
   * another row of this list, and — when {@code far_expect_file} is false (§6.4) — for {@link
   * #closeParkWithFarAmount}.
   */
  public List<ImportCrossCurrencyPark> parkedCrossCurrencyLegs(long importSessionId) {
    return jdbcClient
        .sql(
            """
            select p.import_posting_id     as import_posting_id,
                   p.import_transaction_id as import_transaction_id,
                   t.date                  as date,
                   f.money_account_name    as near_money_account_name,
                   p.money_account_name    as far_money_account_name,
                   p.amount                as amount,
                   la.expect_file          as far_expect_file
              from import_posting p
              join import_transaction t on t.import_transaction_id = p.import_transaction_id
              join import_file f on f.import_file_id = t.import_file_id
              join import_account la on la.import_session_id = f.import_session_id
                                   and la.money_account_name = p.money_account_name
             where f.import_session_id = :sessionId
               and t.state = 'parked'
               and not p.funding
               and p.money_account_name is not null
               and p.counter_amount is null
             order by t.date, p.import_posting_id
            """)
        .param(SESSION_ID, importSessionId)
        .query(ImportCrossCurrencyPark.class)
        .list();
  }

  /**
   * Manually pair two parked cross-currency transfer legs as one transfer's two sightings
   * (import.md §6.5; plan e2b) — the owner's resolution of a shape {@link
   * #matchAndResolveCrossCurrency} could not disambiguate on its own (an ambiguous same-day set, or
   * a cross-currency split leg). Requires both legs to be currently parked, unresolved, non-funding
   * transfer legs of two different transactions whose mapped accounts cross each other and whose
   * amounts are opposite-signed (a real mirror's two legs always are — one is money into the far
   * account, the other money out of it, {@link #RESOLVABLE_CROSS_CURRENCY_PAIRS}'s same guard) —
   * the same shape the automatic pass would have paired had it been unambiguous. A pair where both
   * sides are a split is refused (the same rule {@link #MATCHED_PAIRS} applies to e1): neither can
   * be excluded wholesale, and that residual is e4's problem.
   *
   * <p>Sets {@code counter_amount}/{@code mirror_pair_id} symmetrically — each leg's own signed
   * amount, negated, becomes its counterpart's {@code counter_amount} (the real value that crossed,
   * whether or not that leg's own transaction is a split; a split's <em>funding total</em> covers
   * unrelated category legs too and would overstate the crossed amount) — and applies the same
   * split-aware survivor rule as {@link #matchAndMark}: a split's other legs must still book, so
   * only a wholly-simple sighting is excluded.
   *
   * @return true when the pair was matched; false when the two postings do not form a valid parked
   *     crossing pair
   */
  public boolean manualMatch(long importSessionId, long importPostingId, long mirrorPostingId) {
    if (importPostingId == mirrorPostingId) {
      return false;
    }
    Optional<CrossingLeg> legA = crossingLeg(importSessionId, importPostingId);
    Optional<CrossingLeg> legB = crossingLeg(importSessionId, mirrorPostingId);
    if (legA.isEmpty() || legB.isEmpty()) {
      return false;
    }
    CrossingLeg a = legA.get();
    CrossingLeg b = legB.get();
    if (!isMatchablePair(a, b)) {
      return false;
    }
    setResolution(importPostingId, mirrorPostingId, b.amount().negate());
    setResolution(mirrorPostingId, importPostingId, a.amount().negate());
    setTransactionState(survivorTxnId(a, b), READY);
    setTransactionState(excludedTxnId(a, b), MIRRORED);
    return true;
  }

  /**
   * Whether two parked legs form a valid crossing pair: different transactions, each mapped account
   * naming the other, opposite-signed <strong>nonzero</strong> amounts (a real mirror's own two
   * legs always are — the same guard {@link #RESOLVABLE_CROSS_CURRENCY_PAIRS} applies to the
   * automatic path, which otherwise could accept two unrelated same-direction transfers that happen
   * to cross the same accounts on the same day), and not <em>both</em> a split — the same "both
   * split" refusal {@link #MATCHED_PAIRS} applies to e1 (neither could be excluded wholesale).
   * {@code signum() * signum() < 0} — rather than a bare sign comparison — never misreads a
   * degenerate zero-amount leg as "opposite" to a real one: zero's signum is {@code 0}, so the
   * product is {@code 0}, never negative.
   */
  private static boolean isMatchablePair(CrossingLeg a, CrossingLeg b) {
    boolean crosses =
        a.fileAccountId() == b.namedAccountId() && a.namedAccountId() == b.fileAccountId();
    boolean oppositeSign = a.amount().signum() * b.amount().signum() < 0;
    boolean bothSplit = a.nonFundingLegs() > 1 && b.nonFundingLegs() > 1;
    return crosses && oppositeSign && a.txnId() != b.txnId() && !bothSplit;
  }

  /**
   * The split-aware survivor rule {@link #matchAndMark}'s CASE expression applies over a whole
   * matched set: a simple (one non-funding leg) sighting is wholly excludable; a split's other legs
   * must still book, so it survives; between two simple sightings the earlier-staged (lower id) one
   * is kept, matching {@link #MATCHED_PAIRS}'s tie-break.
   */
  private static long survivorTxnId(CrossingLeg a, CrossingLeg b) {
    if (a.nonFundingLegs() == 1 && b.nonFundingLegs() == 1) {
      return Math.min(a.txnId(), b.txnId());
    }
    return a.nonFundingLegs() == 1 ? b.txnId() : a.txnId();
  }

  private static long excludedTxnId(CrossingLeg a, CrossingLeg b) {
    long survivor = survivorTxnId(a, b);
    return survivor == a.txnId() ? b.txnId() : a.txnId();
  }

  /**
   * Close a park by hand-entering the far-currency amount (import.md §6.4; plan e2b) — for a
   * transfer to an account whose {@code expect-file} was cleared, so its own export will never
   * arrive to supply a real mirror. No {@code mirror_pair_id} is set: there is no second sighting.
   * The owner's typed figure is the only source — nothing here derives or guesses it, but a figure
   * that is zero or carries the wrong sign is nonsense for a transfer leg (§6.2 — {@code
   * counter_amount} stands in for this same leg once the far currency is known, so it must share
   * {@code amount}'s sign) and is refused rather than booked.
   *
   * @return true when the park was closed; false when the posting is not a currently-parked,
   *     unresolved cross-currency transfer leg whose named account's {@code expect-file} is
   *     cleared, or the amount is zero or does not match the leg's own direction
   */
  public boolean closeParkWithFarAmount(
      long importSessionId, long importPostingId, BigDecimal farAmount) {
    Optional<CrossingLeg> parked = crossingLeg(importSessionId, importPostingId);
    if (parked.isEmpty() || parked.get().farExpectFile()) {
      return false;
    }
    CrossingLeg leg = parked.get();
    if (farAmount.signum() == 0 || farAmount.signum() != leg.amount().signum()) {
      return false;
    }
    jdbcClient
        .sql("update import_posting set counter_amount = :amount where import_posting_id = :id")
        .param("amount", farAmount)
        .param("id", importPostingId)
        .update();
    setTransactionState(leg.txnId(), READY);
    return true;
  }

  /**
   * Clear the {@code counter_amount} of any surviving posting whose recorded mirror lies in {@code
   * importFileId} — called <strong>before</strong> that file's cascade delete (plan e2b). The
   * on-delete-set-null FK (V21) already clears {@code mirror_pair_id}; without this, the
   * now-orphaned {@code counter_amount} would be indistinguishable from a legitimate hand-entered
   * far amount (§6.4 — also {@code mirror_pair_id is null}) on the next {@link #rematch}, and could
   * silently book a stale figure.
   */
  public void clearCounterAmountOfMirrorsIn(long importFileId) {
    jdbcClient
        .sql(
            """
            update import_posting p
               set counter_amount = null
              from import_posting mp
              join import_transaction mt on mt.import_transaction_id = mp.import_transaction_id
             where p.mirror_pair_id = mp.import_posting_id
               and mt.import_file_id = :fileId
            """)
        .param(FILE_ID, importFileId)
        .update();
  }

  /**
   * The {@code (session, filename)} counterpart of {@link #clearCounterAmountOfMirrorsIn} — every
   * staged file of that name may be about to be removed (the §2 "replace" clash resolution).
   */
  public void clearCounterAmountOfMirrorsInFilesNamed(long importSessionId, String filename) {
    jdbcClient
        .sql(
            """
            update import_posting p
               set counter_amount = null
              from import_posting mp
              join import_transaction mt on mt.import_transaction_id = mp.import_transaction_id
              join import_file mf on mf.import_file_id = mt.import_file_id
             where p.mirror_pair_id = mp.import_posting_id
               and mf.import_session_id = :sessionId
               and mf.filename = :filename
            """)
        .param(SESSION_ID, importSessionId)
        .param(FILENAME, filename)
        .update();
  }

  /**
   * Every currently-resolved cross-currency transfer leg of a session — automatic match, manual
   * match, or a hand-entered far amount alike (plan e3, import.md §6.3) — as a rate candidate: this
   * leg's own real native amount and the real far-currency amount it resolved to ({@code
   * counter_amount}), the actual conversion rate of a real event on that date. The caller ({@code
   * importer}'s service layer) offers each to {@code ledger}'s {@code ExchangeRateService}, which
   * decides whether the pair states a base-relative rate at all and writes it back — this
   * repository never touches {@code exchange_rate} itself (CLAUDE.md §1: that table belongs to
   * {@code ledger}).
   *
   * <p>Deliberately <strong>not</strong> scoped to "just resolved by this call": it reports every
   * currently-resolved leg of the session every time, relying on the write side's never-overwrite
   * behaviour to make a repeat harmless. This sidesteps any dependency on exactly when a caller
   * fetches it relative to a resolving update, and self-heals if the base currency was set after a
   * resolution already happened. A resolved pair yields this from <strong>both</strong> its
   * sightings (symmetric, like {@link #MATCHED_PAIRS}) — both compute the same implied rate, so
   * which one the write side's {@code on conflict do nothing} keeps does not matter <em>for a
   * genuine pair</em>. Ordered by posting id purely for deterministic test output — it does not
   * settle which of two genuinely <strong>different</strong> same-day rates for one currency wins
   * that write (a real but rare case, since the cache holds one rate per {@code (currency, date)});
   * that residual ambiguity is inherent to the cache's own shape (data-model §3.7), not something
   * this method can resolve, and the caller is free to correct the day's rate by hand afterward the
   * same way any other {@code exchange_rate} row is corrected.
   */
  public List<ImportCrossCurrencyRateCandidate> resolvedCrossCurrencyRateCandidates(
      long importSessionId) {
    return jdbcClient
        .sql(
            """
            select t.date                 as date,
                   coalesce(fa.target_currency_code, fac.currency_code) as currency_a,
                   p.amount                as amount_a,
                   coalesce(la.target_currency_code, lac.currency_code) as currency_b,
                   p.counter_amount        as amount_b
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
               and p.counter_amount is not null
             order by p.import_posting_id
            """)
        .param(SESSION_ID, importSessionId)
        .query(ImportCrossCurrencyRateCandidate.class)
        .list();
  }

  /**
   * One parked, unresolved, cross-currency, non-funding transfer leg's shape — the common lookup
   * behind {@link #manualMatch} and {@link #closeParkWithFarAmount}.
   *
   * @param txnId the leg's staged transaction
   * @param amount this leg's own signed near-currency amount (§6.2) — a resolution's {@code
   *     counter_amount} must carry the same sign, since it stands in for this same leg once the far
   *     currency is known
   * @param nonFundingLegs how many non-funding legs the transaction has (1 = a simple transfer,
   *     wholly excludable when matched; more = a split, whose other legs must still book)
   * @param fileAccountId the mapped Hauptbuch account of the file this leg was staged from
   * @param namedAccountId the mapped Hauptbuch account this leg transfers to
   * @param farExpectFile whether the named account is still awaiting its own export (§5.1) — the
   *     hand-entered path's gate (§6.4)
   */
  private record CrossingLeg(
      long txnId,
      BigDecimal amount,
      int nonFundingLegs,
      long fileAccountId,
      long namedAccountId,
      boolean farExpectFile) {}

  private Optional<CrossingLeg> crossingLeg(long importSessionId, long importPostingId) {
    return jdbcClient
        .sql(
            """
            select p.import_transaction_id as txn_id,
                   p.amount                as amount,
                   (select count(*) from import_posting p2
                     where p2.import_transaction_id = p.import_transaction_id
                       and not p2.funding) as non_funding_legs,
                   fa.account_id as file_account_id,
                   la.account_id as named_account_id,
                   la.expect_file as far_expect_file
              from import_posting p
              join import_transaction t on t.import_transaction_id = p.import_transaction_id
              join import_file f on f.import_file_id = t.import_file_id
              join import_account fa on fa.import_session_id = f.import_session_id
                                   and fa.money_account_name = f.money_account_name
              join import_account la on la.import_session_id = f.import_session_id
                                   and la.money_account_name = p.money_account_name
              left join account fac on fac.account_id = fa.account_id
              left join account lac on lac.account_id = la.account_id
             where p.import_posting_id = :postingId
               and f.import_session_id = :sessionId
               and t.state = 'parked'
               and not p.funding
               and p.money_account_name is not null
               and p.counter_amount is null
               and fa.account_id is not null
               and la.account_id is not null
               and coalesce(fa.target_currency_code, fac.currency_code) is not null
               and coalesce(la.target_currency_code, lac.currency_code) is not null
               and coalesce(fa.target_currency_code, fac.currency_code)
                 <> coalesce(la.target_currency_code, lac.currency_code)
            """)
        .param(POSTING_ID, importPostingId)
        .param(SESSION_ID, importSessionId)
        .query(CrossingLeg.class)
        .optional();
  }

  private void setResolution(long importPostingId, long mirrorPostingId, BigDecimal counterAmount) {
    jdbcClient
        .sql(
            "update import_posting set mirror_pair_id = :mirrorId, counter_amount = :counterAmount"
                + " where import_posting_id = :id")
        .param(MIRROR_ID, mirrorPostingId)
        .param("counterAmount", counterAmount)
        .param("id", importPostingId)
        .update();
  }

  private void setTransactionState(long importTransactionId, String state) {
    jdbcClient
        .sql("update import_transaction set state = :state where import_transaction_id = :id")
        .param("state", state)
        .param("id", importTransactionId)
        .update();
  }
}
