package volkovandr.hauptbuch.receipts;

import java.util.List;

/**
 * One button of the committed-delete dialog (plan §9g) — the ladder's last rung, whose two
 * independent axes (void the booked transaction or keep it · remove the image files or keep them)
 * make four choices plus Cancel. Enumerated here rather than written out four times in the template
 * so the axes stay visibly orthogonal and the wording lives in one place.
 *
 * @param label the button text, naming both consequences
 * @param voidTransaction whether the backing transaction is soft-deleted along with the receipt
 * @param removeFiles whether the scan and its derivatives are removed from disk
 */
public record CommittedDeleteChoice(String label, boolean voidTransaction, boolean removeFiles) {

  /** The four non-cancel choices, safest first (keep everything) to most destructive. */
  public static final List<CommittedDeleteChoice> ALL =
      List.of(
          new CommittedDeleteChoice("Keep the transaction and the files", false, false),
          new CommittedDeleteChoice("Keep the transaction, delete the files", false, true),
          new CommittedDeleteChoice("Void the transaction, keep the files", true, false),
          new CommittedDeleteChoice("Void the transaction and delete the files", true, true));
}
