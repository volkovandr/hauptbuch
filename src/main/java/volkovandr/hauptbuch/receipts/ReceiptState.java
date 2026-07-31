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

  /** The AI call failed; retryable (9e). */
  public static final String FAILED = "failed";

  /** Every valid state, in lifecycle order. */
  public static final List<String> ALL =
      List.of(NEW, PRE_PROCESSED, PROCESSING, PROCESSED, COMMITTED, FAILED);

  /**
   * The register's default "work queue": everything except {@code committed} (§5.2). A committed
   * receipt backs a transaction — it is not outstanding work. ({@code discarded} was retired
   * 2026-07-31; "seen, not booked" is now a soft-delete with files kept, so it never appears.)
   */
  public static final List<String> WORK_QUEUE =
      List.of(NEW, PRE_PROCESSED, PROCESSING, PROCESSED, FAILED);

  /** The states the pre-process editor may be entered from and saved back to (receipt doc §6.1). */
  private static final Set<String> PRE_PROCESSABLE = Set.of(NEW, PRE_PROCESSED);

  private ReceiptState() {}

  /** Whether {@code state} is a known lifecycle value. */
  public static boolean isValid(String state) {
    return ALL.contains(state);
  }

  /**
   * Whether a receipt in this state deletes instantly with its files removed and no confirmation —
   * the top rung of the delete ladder, retained on <em>mobile</em> only (§4). Only a {@code new}
   * scan: nothing downstream depends on it yet, so a bad shot is dropped outright. On PC the delete
   * always asks the 3-way keep/delete-files dialog, {@code new} included (§5.2, 2026-07-31).
   */
  public static boolean deletesInstantly(String state) {
    return NEW.equals(state);
  }

  /**
   * Whether the pre-process editor applies to this state: only {@code new} (first edit) and {@code
   * pre_processed} (re-edit, recipe replayed). Past that the image is locked for the AI (§6.1).
   */
  public static boolean isPreProcessable(String state) {
    return PRE_PROCESSABLE.contains(state);
  }
}
