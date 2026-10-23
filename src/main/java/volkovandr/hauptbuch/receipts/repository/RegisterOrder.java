package volkovandr.hauptbuch.receipts.repository;

/**
 * The columns the receipt register list can be ordered by in SQL (issue tracker #11, receipt issue
 * 39). The other sortable columns are not {@code receipt} columns and are ordered in memory after
 * the fetch, so the SQL order for them is immaterial and {@link #CAPTURED} is used.
 */
public enum RegisterOrder {

  /** The receipt's capture time — the list's default. */
  CAPTURED,

  /** The parsed total; an unparsed (null) total places last ascending, first descending. */
  TOTAL,

  /** When the receipt was last confirmed; receipts never confirmed always place last. */
  CONFIRMED
}
