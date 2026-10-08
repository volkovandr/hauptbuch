package volkovandr.hauptbuch.statements;

import java.util.List;
import volkovandr.hauptbuch.ledger.TransactionTag;

/**
 * What the statement page's dock shows (statements.md §6.4): the line's own facts, plus the
 * editable fields — pre-filled on open, or as typed when a save was refused.
 *
 * @param kind what Save does
 * @param lineId the line being resolved, or 0 for an extra
 * @param postingId the booked leg being amended or edited, or 0 when a line is being created
 * @param amount the amount as a magnitude, German-formatted; for a line it is the bank's and is
 *     read-only (the dock books the bank's sign whatever the category says), for an extra it is the
 *     editable amount text
 * @param bankText the bank's counterparty and description, shown so the operator can see what the
 *     payee was guessed from; empty for an extra
 * @param bankCategory the bank's own category label, shown in the dock's header
 * @param notice what Save will change about the booked transaction, or empty
 * @param input the editable fields: date, payee, category and the rest
 * @param tags the chips for {@code input.tagId()}, with their labels
 * @param error why the last save was refused, or null
 */
public record DockPrefill(
    Kind kind,
    long lineId,
    long postingId,
    String amount,
    String bankText,
    String bankCategory,
    String notice,
    DockInput input,
    List<TransactionTag> tags,
    String error) {

  /** What Save does with the dock. */
  public enum Kind {
    /** Book a transaction for a missing line and match it. */
    CREATE,
    /** Correct a proposed transaction to the bank's figures, then match it. */
    AMEND,
    /** Edit a transaction the statement does not account for; nothing is matched. */
    EXTRA
  }

  /** Defensively copy the chips so the dock cannot be mutated after. */
  public DockPrefill {
    tags = List.copyOf(tags);
  }

  /** Whether the booked transaction is cross-currency, so the counterpart amounts are shown. */
  public boolean crossCurrency() {
    return input.categoryAmount() != null;
  }

  /** Whether the operator may type the amount (an extra) rather than inherit the bank's. */
  public boolean amountEditable() {
    return kind == Kind.EXTRA;
  }

  /** The dock's title. */
  public String title() {
    return switch (kind) {
      case CREATE -> "Create transaction";
      case AMEND -> "Amend transaction";
      case EXTRA -> "Edit transaction";
    };
  }

  /** Where Save posts, relative to the application root. */
  public String path(long statementId) {
    String base = "/statements/" + statementId;
    return switch (kind) {
      case CREATE -> base + "/lines/" + lineId + "/create";
      case AMEND -> base + "/lines/" + lineId + "/amend/" + postingId;
      case EXTRA -> base + "/extras/" + postingId + "/edit";
    };
  }

  /** The label of Save. */
  public String saveLabel() {
    return kind == Kind.EXTRA ? "Save" : "Save and match";
  }
}
