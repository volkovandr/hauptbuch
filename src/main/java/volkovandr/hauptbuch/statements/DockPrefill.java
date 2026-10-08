package volkovandr.hauptbuch.statements;

import java.util.List;
import volkovandr.hauptbuch.ledger.TransactionTag;

/**
 * What the statement page's dock shows for a missing line (statements.md §6.4): the line's own
 * facts, plus the editable fields — pre-filled on open, or as typed when a save was refused.
 *
 * @param lineId the line being created
 * @param amount the line's amount as a magnitude, German-formatted; its direction is the line's
 *     business, and the dock books the bank's sign whatever the category says
 * @param bankText the bank's counterparty and description, shown so the operator can see what the
 *     payee was guessed from
 * @param bankCategory the bank's own category label, shown in the dock's header
 * @param input the editable fields: date, payee, category and the rest
 * @param tags the chips for {@code input.tagId()}, with their labels
 * @param error why the last save was refused, or null
 */
public record DockPrefill(
    long lineId,
    String amount,
    String bankText,
    String bankCategory,
    DockInput input,
    List<TransactionTag> tags,
    String error) {

  /** Defensively copy the chips so the dock cannot be mutated after. */
  public DockPrefill {
    tags = List.copyOf(tags);
  }
}
