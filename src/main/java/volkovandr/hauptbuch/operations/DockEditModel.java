package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import java.util.List;
import volkovandr.hauptbuch.ledger.TransactionTag;
import volkovandr.hauptbuch.ledger.TransferTarget;

/**
 * A transaction pre-filled back into the entry dock (register §3.1) — the dock's own fields,
 * reconstructed from the transaction's legs by {@link DockEditService}. A non-null {@code
 * transactionId} flips the dock into <em>edit</em> mode (Save/Void, re-threads in place); a {@code
 * null} id is a pre-filled <em>new</em> dock (all other fields carried, but a fresh transaction on
 * Save) — used when Cancel returns a split panel to the dock without losing the header and first
 * line (register §3.9).
 *
 * <p>Edit mode covers a <em>simple</em> transaction: one funding own-account leg, one counterpart
 * (category or transfer account). The {@code amount} is reconstructed as the user would type it: a
 * bare magnitude, carrying an explicit leading {@code +}/{@code −} only when the leg's direction is
 * the non-default one for the category's type (a refund, register §3.8), so re-saving round-trips.
 * For cross-currency and transfers (plan stage 7f), {@code categoryAmount} and {@code baseAmount}
 * are populated on the counterpart leg's amounts (data-model §6.4).
 *
 * @param transactionId the transaction being edited (the dock's hidden id)
 * @param date booking date
 * @param accountId the funding account, selected in the dock's Account picker
 * @param payeeText the payee's {@code Name - City - Country} entry value to pre-fill the payee
 *     input; {@code null} for a payee-less transaction
 * @param amount the magnitude the user would type, with a leading {@code +}/{@code −} only when the
 *     direction overrides the category type's default (register §3.8)
 * @param categoryId the semantic category id, or the transfer target account id for transfers
 * @param categoryName the semantic category name, or the transfer target account name
 * @param categoryCurrencyCode the currency override for the counterpart leg; {@code null} for
 *     single-currency transactions
 * @param categoryAmount the counterpart leg's native magnitude (cross-currency or transfers);
 *     {@code null} for single-currency categories
 * @param baseAmount the frozen base-currency magnitude (cross-currency); {@code null} otherwise
 * @param transferDirection {@code TO}/{@code FROM} when the counterpart is a transfer account (plan
 *     stage 7f); {@code null} for a category counterpart
 * @param note the transaction note; {@code null} if none
 * @param tags the transaction's tags (id + canonical label), pre-filled as chip pills so a re-save
 *     preserves them (register §3.6, plan stage 7e); empty when untagged
 */
public record DockEditModel(
    Long transactionId,
    LocalDate date,
    Long accountId,
    String payeeText,
    String amount,
    Long categoryId,
    String categoryName,
    String categoryCurrencyCode,
    String categoryAmount,
    String baseAmount,
    String transferDirection,
    String note,
    List<TransactionTag> tags) {

  /** Defensively copy the tags to an immutable list (null-safe). */
  public DockEditModel {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  /**
   * The value the dock's Category input shows. For a transfer it is the direction-prefixed label
   * ({@code To → Name} / {@code From ← Name}) so the field both displays the direction and
   * re-resolves back to a transfer if the user edits it; for a plain category it is the bare name.
   * Reuses {@link TransferTarget#option} so the {@code To →}/{@code From ←} glyphs stay defined in
   * one place (the register that offers them), never duplicated into the template.
   */
  public String categoryEntryText() {
    if (transferDirection == null) {
      return categoryName;
    }
    return TransferTarget.option(TransferTarget.Direction.valueOf(transferDirection), categoryName);
  }
}
