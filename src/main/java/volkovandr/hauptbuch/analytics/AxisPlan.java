package volkovandr.hauptbuch.analytics;

/**
 * The resolved shape of a {@link ReportSpec}'s two axes (stage a's cap, still true in stage e: at
 * most one non-Date dimension <em>family</em> total, on either axis, plus optionally {@link
 * Dimension#DATE} on the other).
 *
 * @param rowDim the rows dimension, or {@code null} for none — the <em>outer</em> dimension when
 *     rows nests two (§3)
 * @param colDim the columns dimension, or {@code null} for none — the outer dimension when columns
 *     nests two
 * @param nonDateDim whichever of {@code rowDim}/{@code colDim} is not {@link Dimension#DATE}, or
 *     {@code null} when neither axis carries one
 * @param innerDim stage e's second, nested dimension on whichever axis carries {@code nonDateDim}
 *     (§3, §9.2), or {@code null} when that axis carries only one dimension
 * @param dateOnRows whether {@code rowDim == DATE}
 * @param dateOnColumns whether {@code colDim == DATE}
 */
record AxisPlan(
    Dimension rowDim,
    Dimension colDim,
    Dimension nonDateDim,
    Dimension innerDim,
    boolean dateOnRows,
    boolean dateOnColumns) {

  /** Stage a/b/d shape: no second, nested dimension. */
  AxisPlan(
      Dimension rowDim,
      Dimension colDim,
      Dimension nonDateDim,
      boolean dateOnRows,
      boolean dateOnColumns) {
    this(rowDim, colDim, nonDateDim, null, dateOnRows, dateOnColumns);
  }
}
