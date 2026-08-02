package volkovandr.hauptbuch.receipts;

import volkovandr.hauptbuch.operations.SplitLineView;

/**
 * One rendered line of the post-process editor (plan §9f): the shared line-editor core's view
 * ({@link SplitLineView}, reused verbatim from the register's split panel) plus this surface's own
 * {@code ghost} — the AI's raw target term shown as a grey "AI said: …" hint on an unresolved line
 * or a provenance tooltip on a resolved one (data-model §13.2). Null ghost = the AI named no
 * target.
 *
 * @param view the shared line-editor view model (category/transfer/person, amount, note, tags)
 * @param ghost the AI's raw target term for this line, or null
 * @param description the parsed item name — the receipts surface's own leading column (the register
 *     split has no per-line description), rendered by the shared fragment only when non-null
 */
public record ReceiptEditorLine(SplitLineView view, String ghost, String description) {}
