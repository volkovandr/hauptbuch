package volkovandr.hauptbuch.analytics;

/**
 * The resolved shape of a {@link ReportSpec}'s two axes (stage a's cap: at most one non-Date
 * dimension total, on either axis, plus optionally {@link Dimension#DATE} on the other).
 *
 * @param rowDim the rows dimension, or {@code null} for none
 * @param colDim the columns dimension, or {@code null} for none
 * @param nonDateDim whichever of {@code rowDim}/{@code colDim} is not {@link Dimension#DATE}, or
 *     {@code null} when neither axis carries one
 * @param dateOnRows whether {@code rowDim == DATE}
 * @param dateOnColumns whether {@code colDim == DATE}
 */
record AxisPlan(
    Dimension rowDim,
    Dimension colDim,
    Dimension nonDateDim,
    boolean dateOnRows,
    boolean dateOnColumns) {}
