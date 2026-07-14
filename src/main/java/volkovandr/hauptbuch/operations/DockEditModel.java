package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import java.util.List;
import volkovandr.hauptbuch.ledger.TransactionTag;

/**
 * A transaction pre-filled back into the entry dock (register §3.1) — the dock's own fields,
 * reconstructed from the transaction's legs by {@link DockEditService}. A non-null {@code
 * transactionId} flips the dock into <em>edit</em> mode (Save/Void, re-threads in place); a {@code
 * null} id is a pre-filled <em>new</em> dock (all other fields carried, but a fresh transaction on
 * Save) — used when Cancel returns a split panel to the dock without losing the header and first
 * line (register §3.9).
 *
 * <p>Edit mode covers a <em>simple</em> transaction (one funding own-account leg, one category leg,
 * single currency) — the shape the dock creates at 7b. Splits arrive later in 7c; transfers and
 * cross-currency at 7d. The {@code amount} is reconstructed as the user would type it: a bare
 * magnitude, carrying an explicit leading {@code +}/{@code −} only when the leg's direction is the
 * non-default one for the category's type (a refund, register §3.8), so re-saving round-trips.
 *
 * @param transactionId the transaction being edited (the dock's hidden id)
 * @param date booking date
 * @param accountId the funding account, selected in the dock's Account picker
 * @param payeeText the payee's {@code Name - City - Country} entry value to pre-fill the payee
 *     input; {@code null} for a payee-less transaction
 * @param amount the magnitude the user would type, with a leading {@code +}/{@code −} only when the
 *     direction overrides the category type's default (register §3.8)
 * @param categoryId the semantic category id (the parent when the leg hits a per-currency leaf, so
 *     re-saving routes back through {@code resolveCurrencyLeaf} correctly — data-model §6.5)
 * @param categoryName the semantic category name, to pre-fill the category input text
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
    String note,
    List<TransactionTag> tags) {

  /** Defensively copy the tags to an immutable list (null-safe). */
  public DockEditModel {
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
