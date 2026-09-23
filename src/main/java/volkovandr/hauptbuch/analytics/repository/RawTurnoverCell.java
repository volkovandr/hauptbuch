package volkovandr.hauptbuch.analytics.repository;

import java.math.BigDecimal;

/**
 * One raw group from a turnover query (reporting.md §5.1): a single (dimension value, Date bucket,
 * currency) triple. Dimension-agnostic on purpose — {@link #dimensionKey()} names whichever
 * hierarchy node or flat value the query grouped by, and {@link ReportQueryRepository} always
 * returns the Date bucket alongside it (§5.2: turnover sums legally across time), so the engine —
 * not the query — decides which of the two is rendered on rows, on columns, or collapsed away.
 *
 * @param dimensionKey the grouped dimension value's stable key (e.g. a top-level account id)
 * @param dimensionLabel its display label
 * @param dimensionType the backing account's {@code type}, for the credit-natural display flip
 *     (data-model §4.1); {@code null} for a dimension with no single natural type
 * @param bucketKey the Date bucket's own stable key (reporting.md §8.2), whose format follows the
 *     query's granularity — {@code yyyy-MM} for a month bucket, {@code yyyy-MM-dd} for a day/week
 *     bucket, {@code yyyy} for a year bucket
 * @param currencyCode the native currency of the postings in this group
 * @param nativeAmount the leg-filtered signed sum in {@code currencyCode}
 * @param baseAmount the same sum valued posting-by-posting in base (data-model §6.1); {@code null}
 *     when a contributing posting had no usable rate
 * @param missingRateCount postings that could not be valued in base — a non-zero count makes the
 *     base-currency cell {@code —} rather than a silently partial figure
 * @param postingCount postings contributing to this group
 * @param transactionCount distinct transactions contributing to this group
 */
public record RawTurnoverCell(
    String dimensionKey,
    String dimensionLabel,
    String dimensionType,
    String bucketKey,
    String currencyCode,
    BigDecimal nativeAmount,
    BigDecimal baseAmount,
    long missingRateCount,
    long postingCount,
    long transactionCount) {

  /** This cell re-keyed under {@code key} — a nested child row's composite key (§9.1). */
  public RawTurnoverCell withDimensionKey(String key) {
    return new RawTurnoverCell(
        key,
        dimensionLabel,
        dimensionType,
        bucketKey,
        currencyCode,
        nativeAmount,
        baseAmount,
        missingRateCount,
        postingCount,
        transactionCount);
  }

  /** This cell moved to bucket {@code key} — an expanded Date row's own day (§9.1). */
  public RawTurnoverCell withBucketKey(String key) {
    return new RawTurnoverCell(
        dimensionKey,
        dimensionLabel,
        dimensionType,
        key,
        currencyCode,
        nativeAmount,
        baseAmount,
        missingRateCount,
        postingCount,
        transactionCount);
  }
}
