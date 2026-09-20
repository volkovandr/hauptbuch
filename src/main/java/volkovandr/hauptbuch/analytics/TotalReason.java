package volkovandr.hauptbuch.analytics;

/**
 * Whether a row, column or grand total is structurally forbidden (reporting.md §7.2) — the
 * tag/time/multi-measure rules {@link ReportGridBuilder} applies before it ever sums a cell. Split
 * out of {@link ReportGridBuilder} purely to keep that class's own cyclomatic complexity down:
 * these three checks share no state with the rest of the grid build.
 */
final class TotalReason {

  private TotalReason() {}

  /** A row total sums across every rendered column (§7.1). */
  static Cell.Reason forRowTotal(
      ReportSpec spec, AxisPlan axes, boolean forbiddenByTag, boolean anyClosingBalance) {
    if (forbiddenByTag) {
      return Cell.Reason.CROSS_TAG_TOTAL;
    }
    // Different measures in one row (e.g. base next to native) are different presentations of one
    // figure, not additive quantities — summing them would silently double-count.
    if (spec.measures().size() > 1) {
      return Cell.Reason.MULTI_MEASURE_TOTAL;
    }
    if (axes.colDim() == Dimension.DATE && anyClosingBalance) {
      return Cell.Reason.TIME_AXIS_BALANCE;
    }
    return null;
  }

  /** A column total sums down every row of one rendered column (§7.1). */
  static Cell.Reason forColumnTotal(
      boolean forbiddenByTag, AxisPlan axes, MeasureKind columnMeasureKind) {
    if (forbiddenByTag) {
      return Cell.Reason.CROSS_TAG_TOTAL;
    }
    if (axes.rowDim() == Dimension.DATE && columnMeasureKind == MeasureKind.CLOSING_BALANCE) {
      return Cell.Reason.TIME_AXIS_BALANCE;
    }
    return null;
  }

  /** The one cell where both totals meet. */
  static Cell.Reason forGrandTotal(
      boolean rowTotalsForbiddenByTag,
      boolean columnTotalsForbiddenByTag,
      AxisPlan axes,
      boolean anyClosingBalance) {
    if (rowTotalsForbiddenByTag || columnTotalsForbiddenByTag) {
      return Cell.Reason.CROSS_TAG_TOTAL;
    }
    boolean dateAxis = axes.rowDim() == Dimension.DATE || axes.colDim() == Dimension.DATE;
    if (dateAxis && anyClosingBalance) {
      return Cell.Reason.TIME_AXIS_BALANCE;
    }
    return null;
  }
}
