package volkovandr.hauptbuch.operations;

import java.time.LocalDate;

/**
 * A live transaction pre-filled back into the entry dock's <em>edit</em> mode (register §3.1) — the
 * dock's own fields, reconstructed from the transaction's legs by {@link DockEditService}, plus the
 * {@code transactionId} that flips the dock from "new" to "edit". Saving re-threads the transaction
 * in place; the same dock form posts to the same commit endpoint, carrying this id so the commit
 * edits rather than records.
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
 */
public record DockEditModel(
    long transactionId,
    LocalDate date,
    long accountId,
    String payeeText,
    String amount,
    long categoryId,
    String categoryName,
    String note) {}
