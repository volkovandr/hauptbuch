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
 * <p>Cross-currency (register §3.8a/§3.10, plan stage 7d.2): the typed {@code amount} is the
 * spending-currency figure on the receipt; its funding-account and base equivalents ({@code
 * accountAmount}, {@code baseAmount}) are <em>derived</em> from the header's shared rate and shown
 * <em>read-only</em> beside the line. Both are {@code ""} for a single-currency split (no
 * conversion, nothing to derive).
 *
 * @param index the line's zero-based position (drives its resolve target id)
 * @param categoryText the category text to prefill
 * @param categoryId the resolved category id; {@code ""} until resolved
 * @param categoryType the resolved category type ({@code income}/{@code expense}); {@code ""} until
 *     resolved
 * @param transferDirection the line's transfer direction ({@code TO}/{@code FROM}) when it resolved
 *     to a {@code To →}/{@code From ←} transfer target (register §3.8, plan stage 7d.3), else
 *     {@code ""} — the keyboard.js leaf reads it (via {@code lineTransferDirection}) to sign a
 *     transfer line in the live readout, which has no category type
 * @param amount the typed amount to prefill (a bare magnitude, optionally a leading {@code −}
 *     storno), in the spending currency
 * @param note the line's posting-level note; {@code ""} if none
 * @param accountAmount the derived funding-currency equivalent, read-only; {@code ""} when single-
 *     currency
 * @param baseAmount the derived base-currency equivalent, read-only (the last line absorbs the
 *     rounding residual so the column sums to the base total exactly); {@code ""} when single-
 *     currency
 */
public record SplitLineView(
    int index,
    String categoryText,
    String categoryId,
    String categoryType,
    String transferDirection,
    String amount,
    String note,
    String accountAmount,
    String baseAmount) {}
