package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;

/**
 * The raw query results {@link ReportEngine} fetched for a Report, keyed for {@link
 * ReportGridBuilder} to look up by whichever axis carries a wired dimension.
 *
 * @param turnoverByLeg one raw-cell list per distinct {@link Leg} a turnover measure needs
 * @param balanceByBucketKey one raw-cell list per date-axis bucket key (or the {@code "total"}
 *     sentinel when neither axis is {@link Dimension#DATE})
 * @param asOfByBucketKey the as-of date used for each entry in {@code balanceByBucketKey} — needed
 *     again for the base-currency rate lookup, which must use the same date as the query did
 */
record GridData(
    Map<Leg, List<RawTurnoverCell>> turnoverByLeg,
    Map<String, List<RawBalanceCell>> balanceByBucketKey,
    Map<String, LocalDate> asOfByBucketKey) {

  /**
   * This data plus {@code days} — one expanded Date row bucket's own day-granularity fetch
   * (reporting.md §9.1) — with each day's bucket key prefixed {@code "<bucketKey>|"}, matching the
   * day rows {@link ReportGridBuilder#dateFrontierNodes} gives them.
   */
  GridData withDays(String bucketKey, GridData days) {
    String prefix = bucketKey + "|";
    Map<Leg, List<RawTurnoverCell>> turnover = new EnumMap<>(Leg.class);
    turnoverByLeg.forEach((leg, cells) -> turnover.put(leg, new ArrayList<>(cells)));
    days.turnoverByLeg()
        .forEach(
            (leg, cells) ->
                turnover
                    .computeIfAbsent(leg, l -> new ArrayList<>())
                    .addAll(
                        cells.stream().map(c -> c.withBucketKey(prefix + c.bucketKey())).toList()));
    Map<String, List<RawBalanceCell>> balance = new LinkedHashMap<>(balanceByBucketKey);
    days.balanceByBucketKey().forEach((key, cells) -> balance.put(prefix + key, cells));
    Map<String, LocalDate> asOf = new LinkedHashMap<>(asOfByBucketKey);
    days.asOfByBucketKey().forEach((key, date) -> asOf.put(prefix + key, date));
    return new GridData(turnover, balance, asOf);
  }
}
