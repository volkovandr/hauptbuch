package volkovandr.hauptbuch.analytics.repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * One raw group from a closing-balance query (reporting.md §5.1): the native standing balance of a
 * dimension value, in one currency, as of a single date. The base valuation (native × rate-as-of,
 * data-model §6.1) is applied by the engine, one rate lookup per currency — unlike turnover, a
 * balance is valued once at the report date, not posting-by-posting.
 *
 * @param dimensionKey the grouped dimension value's stable key (e.g. a top-level account id)
 * @param dimensionLabel its display label
 * @param dimensionType the backing account's {@code type}, for the credit-natural display flip
 * @param currencyCode the native currency of this balance
 * @param nativeBalance the cumulative native balance as of the query's {@code asOf} date
 * @param postingIds the ids of this group's postings inside the cell's period, when a drill-down
 *     asks for them ({@link QueryConstraints#periodStart()}, reporting.md §12); empty otherwise
 */
public record RawBalanceCell(
    String dimensionKey,
    String dimensionLabel,
    String dimensionType,
    String currencyCode,
    BigDecimal nativeBalance,
    List<Long> postingIds) {

  /** Defensively copies the posting ids. */
  public RawBalanceCell {
    postingIds = List.copyOf(postingIds);
  }

  /** A group without posting ids — every caller but the drill-down. */
  public RawBalanceCell(
      String dimensionKey,
      String dimensionLabel,
      String dimensionType,
      String currencyCode,
      BigDecimal nativeBalance) {
    this(dimensionKey, dimensionLabel, dimensionType, currencyCode, nativeBalance, List.of());
  }

  /** This cell re-keyed under {@code key} — a nested child row's composite key (§9.1). */
  public RawBalanceCell withDimensionKey(String key) {
    return new RawBalanceCell(
        key, dimensionLabel, dimensionType, currencyCode, nativeBalance, postingIds);
  }
}
