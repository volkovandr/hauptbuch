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
 * <p><strong>Transfer lines (register §3.5/§3.8, plan stage 7d.3).</strong> A line whose Category
 * field named {@code To → <account>} / {@code From ← <account>} is a <em>transfer</em> to a real
 * own account: {@code categoryId} then carries that account's id (not a category), no currency leaf
 * is resolved (its currency is fixed by the account), and {@code transferDirection} — {@code
 * TO}/{@code FROM} — supplies the sign the way a category type otherwise would ({@code TO} = an
 * outflow from the funding account, like an expense; {@code FROM} = an inflow, like income). A
 * storno still flows through. Null/blank {@code transferDirection} is the ordinary category line.
 *
 * @param categoryId the semantically-picked category (a leaf or a subdivided parent), or — when
 *     {@code transferDirection} is set — the real own account the transfer leg hits; the
 *     per-currency leaf is resolved at commit from the funding account's currency (data-model §6.5)
 * @param amount the bare magnitude as typed, German-formatted, optionally a leading {@code −}
 *     storno
 * @param note free-text posting-level note for this line (register §3.7); nullable
 * @param transferDirection {@code TO}/{@code FROM} when this line is a transfer to a real own
 *     account (register §3.8); null/blank for an ordinary category line
 */
public record SplitLineDraft(
    long categoryId, String amount, String note, String transferDirection) {}
