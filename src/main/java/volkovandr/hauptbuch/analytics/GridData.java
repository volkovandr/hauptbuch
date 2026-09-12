package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
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
    Map<String, LocalDate> asOfByBucketKey) {}
