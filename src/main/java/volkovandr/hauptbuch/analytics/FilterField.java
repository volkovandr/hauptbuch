package volkovandr.hauptbuch.analytics;

/**
 * What a {@link ReportFilter} constrains — the seven dimension-backed fields plus the two
 * filter-only fields that are never dimensions (reporting.md §4): {@link #RECONCILIATION} and
 * {@link #NOTE} partition nothing worth reading down a page. {@code lifecycle} is deliberately not
 * a filter field — the {@code includePendingReview} scope toggle (§6.4) already decides it, and the
 * only thing a filter would add ("pending only") is a register worklist question, not a reporting
 * one (§6.3).
 */
public enum FilterField {
  CATEGORY,
  ACCOUNT,
  TAG,
  PAYEE,
  PERSON,
  CURRENCY,
  ACCOUNT_TYPE,
  RECONCILIATION,
  NOTE
}
