package volkovandr.hauptbuch.operations;

/**
 * One rendered line of the split panel (register §3.10, plan stage 7c.2) — the values the panel
 * fragment prints back into the line's inputs so a re-render (add/remove line, or an error
 * redisplay) preserves everything the user has entered and resolved.
 *
 * <p>The {@code index} positions the line's hidden-field targets ({@code #line-{index}-resolved})
 * for the per-line {@code /categories/resolve} swap. {@code categoryType} is the resolved leaf type
 * ({@code income}/{@code expense}) that the keyboard.js leaf reads (via {@code data-split-type}) to
 * sign this line's contribution in the live remaining/direction readout; empty until the line's
 * category resolves.
 *
 * @param index the line's zero-based position (drives its resolve target id)
 * @param categoryText the category text to prefill
 * @param categoryId the resolved category id; {@code ""} until resolved
 * @param categoryType the resolved category type ({@code income}/{@code expense}); {@code ""} until
 *     resolved
 * @param amount the typed amount to prefill (a bare magnitude, optionally a leading {@code −}
 *     storno)
 * @param note the line's posting-level note; {@code ""} if none
 */
public record SplitLineView(
    int index,
    String categoryText,
    String categoryId,
    String categoryType,
    String amount,
    String note) {}
