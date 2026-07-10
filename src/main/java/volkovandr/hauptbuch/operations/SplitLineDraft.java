package volkovandr.hauptbuch.operations;

/**
 * One line of a split transaction as submitted from the split panel (register §3.10, plan stage
 * 7c.2) — the raw fields before {@link DockSplitService} routes the category to its per-currency
 * leaf and signs the line by that leaf's type.
 *
 * <p>The {@code amount} is a bare German-formatted magnitude the user types <em>without</em> a sign
 * in the normal case: the line's <em>category type</em> supplies the sign (income → the funding
 * account is credited its share, expense → debited). A leading {@code −} is honoured as a
 * <em>storno</em> (a reversal of that line) and flows through with its sign (register §3.8, the
 * mixed-split rule ratified 2026-07-09) — a negative on an income line counts negatively, a
 * negative on an expense line counts positively.
 *
 * @param categoryId the semantically-picked category (a leaf or a subdivided parent); the
 *     per-currency leaf is resolved at commit from the funding account's currency (data-model §6.5)
 * @param amount the bare magnitude as typed, German-formatted, optionally a leading {@code −}
 *     storno
 * @param note free-text posting-level note for this line (register §3.7); nullable
 */
public record SplitLineDraft(long categoryId, String amount, String note) {}
