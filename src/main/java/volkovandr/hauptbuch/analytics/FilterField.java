package volkovandr.hauptbuch.analytics;

/**
 * What a {@link ReportFilter} constrains — the seven dimension-backed fields plus the three
 * filter-only fields that are never dimensions (reporting.md §4): {@link #LIFECYCLE}, {@link
 * #RECONCILIATION} and {@link #NOTE} partition nothing worth reading down a page.
 */
public enum FilterField {
  CATEGORY,
  ACCOUNT,
  TAG,
  PAYEE,
  PERSON,
  CURRENCY,
  ACCOUNT_TYPE,
  LIFECYCLE,
  RECONCILIATION,
  NOTE
}
