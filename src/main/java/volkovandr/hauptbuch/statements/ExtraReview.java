package volkovandr.hauptbuch.statements;

/**
 * An extra with its computed boundary label (statements.md §6.3).
 *
 * @param extra the leg
 * @param boundary which neighbouring statement it probably belongs on
 */
public record ExtraReview(StatementExtra extra, Boundary boundary) {

  /** Whether an extra sits in the window at either end of the period. */
  public enum Boundary {
    /** An ordinary extra; it counts against green. */
    NONE(""),
    /** Dated in the last days of the period: probably on the next statement. */
    NEXT("probably on the next statement"),
    /** Dated in the first days of the period: probably on the previous statement. */
    PREVIOUS("probably on the previous statement");

    private final String text;

    Boundary(String label) {
      this.text = label;
    }

    /** The label shown beside the extra. */
    public String label() {
      return text;
    }
  }

  /** Whether the extra counts against green. */
  public boolean boundaryExtra() {
    return boundary != Boundary.NONE;
  }
}
