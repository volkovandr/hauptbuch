package volkovandr.hauptbuch.analytics;

/**
 * The two filter readings (reporting.md §6.2): a transaction-level filter admits a whole
 * transaction the moment any one of its postings matches, then the measure sums whatever the scope
 * selects; a posting-level filter restricts the measured postings themselves.
 */
public enum FilterLevel {
  TRANSACTION,
  POSTING
}
