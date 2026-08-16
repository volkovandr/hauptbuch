package volkovandr.hauptbuch.receipts;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The register list's sort request tokens (issue tracker #11) and how they resolve to a valid
 * (column, direction) pair — the query-param sibling of {@link ReceiptFilters}, kept separate since
 * sorting reshapes row order, not row membership.
 *
 * <p>Two of the four columns are real {@code receipt} columns and sort in SQL ({@link
 * volkovandr.hauptbuch.receipts.repository.ReceiptRepository#findForRegister}); the other two (Txn
 * date, Merchant) aren't columns at all — both are already resolved as batched, per-list lookups
 * (issue trackers #09, #07) — so those sort in memory afterward ({@link #sortByLookup}), reusing
 * the very same lookups the cells already render from.
 */
final class ReceiptSort {

  /** Sort key: the receipt's own capture timestamp — a real column, SQL-sortable. */
  static final String CAPTURED = "captured";

  /** Sort key: the linked transaction's booking date (issue tracker #09) — not a column. */
  static final String TXN_DATE = "txn_date";

  /** Sort key: the resolved Merchant cell text (issue tracker #07) — not a column. */
  static final String MERCHANT = "merchant";

  /** Sort key: the parsed total amount — a real column, SQL-sortable. */
  static final String TOTAL = "total";

  static final String ASC = "asc";
  static final String DESC = "desc";

  private static final List<String> COLUMNS = List.of(CAPTURED, TXN_DATE, MERCHANT, TOTAL);

  private ReceiptSort() {}

  /** An unrecognised or absent sort key falls back to the default: {@link #CAPTURED}. */
  static String resolveColumn(String column) {
    return column != null && COLUMNS.contains(column) ? column : CAPTURED;
  }

  /**
   * An unrecognised or absent direction falls back to {@code column}'s natural first-click
   * direction ({@link #defaultDirectionFor}). {@code column} must already be a resolved, valid key.
   */
  static String resolveDirection(String column, String direction) {
    return ASC.equals(direction) || DESC.equals(direction)
        ? direction
        : defaultDirectionFor(column);
  }

  /**
   * Each column's natural first-click direction: dates and Total lead with the newest/largest first
   * ({@link #DESC}); Merchant leads A→Z ({@link #ASC}).
   */
  static String defaultDirectionFor(String column) {
    return MERCHANT.equals(column) ? ASC : DESC;
  }

  static boolean isDescending(String direction) {
    return DESC.equals(direction);
  }

  /**
   * Re-order an already-fetched register list by a batched lookup keyed on receipt id — how Txn
   * date and Merchant sort, since neither is a real column: the caller passes the very same {@link
   * ReceiptService#transactionDates}/{@link ReceiptService#merchantDisplays} map its cells already
   * render from, not a re-derived one, plus the order to compare its values by (e.g. {@link
   * String#CASE_INSENSITIVE_ORDER} for Merchant's A→Z, matching {@code RegisterService}'s own
   * name-sort convention). A receipt absent from {@code lookup} (not yet committed, or with neither
   * a payee nor a parsed merchant) places by the standard SQL null convention: last ascending,
   * first descending. Ties break by capture date (same direction as {@code descending}), then
   * receipt id.
   */
  static <T> List<Receipt> sortByLookup(
      List<Receipt> receipts, Map<Long, T> lookup, Comparator<T> valueOrder, boolean descending) {
    Comparator<T> values = descending ? valueOrder.reversed() : valueOrder;
    Comparator<Receipt> byValue =
        Comparator.comparing(
            (Receipt r) -> lookup.get(r.receiptId()),
            descending ? Comparator.nullsFirst(values) : Comparator.nullsLast(values));
    Comparator<Receipt> byCapturedThenId =
        Comparator.comparing(
                Receipt::capturedAt, ReceiptSort.<OffsetDateTime>maybeReversed(descending))
            .thenComparing(Receipt::receiptId, maybeReversed(descending));
    return receipts.stream().sorted(byValue.thenComparing(byCapturedThenId)).toList();
  }

  /** Natural order, or reversed when {@code descending} — the shared shape of every tiebreak. */
  private static <U extends Comparable<? super U>> Comparator<U> maybeReversed(boolean descending) {
    Comparator<U> natural = Comparator.naturalOrder();
    return descending ? natural.reversed() : natural;
  }
}
