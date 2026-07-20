package volkovandr.hauptbuch.operations;

import java.util.List;

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
 * <p><strong>Person lines (register §3.5, plan stage 8b.2, data-model §7).</strong> A line whose
 * Category field named {@code for <person>} / {@code by <person>} attributes that line to a person:
 * {@code categoryId} is {@code null} (there is no id to carry — the person's per-currency debt leaf
 * is auto-provisioned at commit, once the line's currency is known), and {@code personDirection} —
 * {@code FOR}/{@code BY} — supplies the sign, exactly as a transfer's does. This is what makes
 * multi-person attribution a per-line property: one receipt splits "€21,50 my food, €10 for Max".
 *
 * @param categoryId the semantically-picked category (a leaf or a subdivided parent), or — when
 *     {@code transferDirection} is set — the real own account the transfer leg hits; the
 *     per-currency leaf is resolved at commit from the funding account's currency (data-model
 *     §6.5). {@code null} for a person line, whose leaf does not exist until commit
 * @param amount the bare magnitude as typed, German-formatted, optionally a leading {@code −}
 *     storno
 * @param note free-text posting-level note for this line (register §3.7); nullable
 * @param transferDirection {@code TO}/{@code FROM} when this line is a transfer to a real own
 *     account (register §3.8); null/blank for an ordinary category line
 * @param personName the attributed person's name when this line is a {@code for}/{@code by} line
 *     (register §3.5); null/blank otherwise
 * @param personDirection {@code FOR}/{@code BY} alongside {@code personName} (data-model §7)
 * @param personRevive the panel's Restore ({@code "true"}) / Create-new decision for a name that
 *     matched only a soft-deleted person; null when no revival was in question
 * @param tagIds this line's own tags (register §3.6, plan stage 7e.3) — the chips on the line,
 *     already carrying any transaction-level tags inherited (and visibly removable) per §3.6, so
 *     {@link DockSplitService} attaches exactly these to the line's category leg. The funding leg
 *     instead carries the transaction-level tags (data-model §10.2, owner decision 2026-07-14).
 *     Never null; defaults empty
 */
public record SplitLineDraft(
    Long categoryId,
    String amount,
    String note,
    String transferDirection,
    String personName,
    String personDirection,
    String personRevive,
    List<Long> tagIds) {

  /** Defensively copy the tag ids (null-safe) so the draft cannot be mutated after. */
  public SplitLineDraft {
    tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
  }
}
