package volkovandr.hauptbuch.receipts;

import java.util.List;
import java.util.Set;

/**
 * The receipt lifecycle states (data-model §13.1) and the small predicates the 9b surfaces read off
 * them — the delete ladder's rungs and the register's default work queue.
 *
 * <p>Coded as {@code String} constants rather than a Java {@code enum} to match the project's
 * text-coded column convention ({@code account.type}, {@code posting.reconciliation}) and map
 * straight through {@code JdbcClient} without a converter. The states are stored verbatim in the
 * {@code receipt.state} check constraint.
 */
public final class ReceiptState {

  /** Freshly captured; image on disk, nothing parsed. The only state a 9b capture produces. */
  public static final String NEW = "new";

  /** Cleaned and annotated, ready for the AI (9c). */
  public static final String PRE_PROCESSED = "pre_processed";

  /** Handed to the AI; awaiting a result (9e/9h). */
  public static final String PROCESSING = "processing";

  /** AI returned; draft lines seeded, under review (9e/9f). */
  public static final String PROCESSED = "processed";

  /** Booked: backs a transaction (9g). */
  public static final String COMMITTED = "committed";

  /** Looked at and deliberately not booked — kept for the record (orthogonal to soft-delete). */
  public static final String DISCARDED = "discarded";

  /** The AI call failed; retryable (9e). */
  public static final String FAILED = "failed";

  /** Every valid state, in lifecycle order. */
  public static final List<String> ALL =
      List.of(NEW, PRE_PROCESSED, PROCESSING, PROCESSED, COMMITTED, DISCARDED, FAILED);

  /**
   * The register's default "work queue": everything except the two terminal states (§5.2). A
   * committed receipt backs a transaction; a discarded one was set aside on purpose — neither is
   * outstanding work.
   */
  public static final List<String> WORK_QUEUE =
      List.of(NEW, PRE_PROCESSED, PROCESSING, PROCESSED, FAILED);

  private static final Set<String> NON_COMMITTED =
      Set.of(NEW, PRE_PROCESSED, PROCESSING, PROCESSED, DISCARDED, FAILED);

  private ReceiptState() {}

  /** Whether {@code state} is a known lifecycle value. */
  public static boolean isValid(String state) {
    return ALL.contains(state);
  }

  /**
   * Whether a receipt in this state deletes instantly with its files removed and no confirmation —
   * the top rung of the delete ladder (9b), available on both mobile and PC. Only a {@code new}
   * scan: nothing downstream depends on it yet, so a bad shot is discarded outright.
   */
  public static boolean deletesInstantly(String state) {
    return NEW.equals(state);
  }

  /** Whether a receipt in this state may be discarded (any non-committed state). */
  public static boolean canDiscard(String state) {
    return NON_COMMITTED.contains(state);
  }
}
