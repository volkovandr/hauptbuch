package volkovandr.hauptbuch.receipts;

import java.time.LocalDate;
import java.util.List;

/**
 * The register's state + capture-date-range filter tokens (§5.2) and how they resolve to a query.
 * Shared by the register list and the processing screen (§6): the screen carries the register's
 * current filter so its ↑/↓ navigation walks the very same ordered list the register shows.
 */
final class ReceiptFilters {

  /** State filter: the default work queue (everything except {@code committed}). */
  static final String STATE_QUEUE = "queue";

  /** State filter: every state. */
  static final String STATE_ALL = "all";

  /** Date-range filter: the last 90 days of captures (the default). */
  static final String RANGE_90D = "d90";

  /** Date-range filter: the last year. */
  static final String RANGE_1Y = "y1";

  /** Date-range filter: unbounded. */
  static final String RANGE_ALL = "all";

  private ReceiptFilters() {}

  /** The state filter's state set: the work queue, everything, or a single named state. */
  static List<String> statesFor(String state) {
    if (STATE_ALL.equals(state)) {
      return ReceiptState.ALL;
    }
    if (ReceiptState.isValid(state)) {
      return List.of(state);
    }
    return ReceiptState.WORK_QUEUE;
  }

  /**
   * The date-range filter's lower bound: last 90 days (default), last year, or unbounded (null).
   */
  static LocalDate rangeFrom(String range) {
    return switch (range) {
      case RANGE_ALL -> null;
      case RANGE_1Y -> LocalDate.now().minusYears(1);
      default -> LocalDate.now().minusDays(90);
    };
  }
}
