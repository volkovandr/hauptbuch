package volkovandr.hauptbuch.analytics;

/**
 * The two report measures (reporting.md §5.1) plus the two count measures (§5.5). {@link #TURNOVER}
 * and {@link #CLOSING_BALANCE} carry data-model §6.1's two valuation rules and must never be
 * unified: turnover sums postings valued at each posting's own date's rate; closing balance values
 * the standing native balance at the report date's rate (mark-to-market).
 */
public enum MeasureKind {
  TURNOVER,
  CLOSING_BALANCE,
  COUNT_POSTINGS,
  COUNT_TRANSACTIONS
}
