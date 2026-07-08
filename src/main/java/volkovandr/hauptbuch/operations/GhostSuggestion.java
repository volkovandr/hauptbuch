package volkovandr.hauptbuch.operations;

/**
 * The dock's ghost category suggestion for a payee (register §3.9, plan stage 7b): the single
 * most-common category that payee's past transactions used — a fuel station suggests {@code Fuel}.
 * It is the semantic category the user would pick (the parent when the counterpart was a
 * per-currency leaf, §6.5), so accepting it and routing by the new payment's currency lands the
 * right leaf.
 *
 * @param categoryId the suggested category's account id (a leaf, or a subdivided parent)
 * @param categoryName its display name, for the ghost chip
 */
public record GhostSuggestion(long categoryId, String categoryName) {}
