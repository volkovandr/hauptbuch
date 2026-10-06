package volkovandr.hauptbuch.statements;

import java.util.List;

/**
 * The live result of matching a statement (statements.md §4.5): recomputed on every view, never
 * stored.
 *
 * @param lines every line with its status
 * @param extras the extras, with boundary labels
 */
public record StatementReview(List<LineReview> lines, List<ExtraReview> extras) {

  /** Defensive copy of the lists (the house pattern for record lists). */
  public StatementReview {
    lines = List.copyOf(lines);
    extras = List.copyOf(extras);
  }

  /** Lines with a confirmed match. */
  public long matched() {
    return count(LineStatus.MATCHED);
  }

  /** Matched lines whose posting is {@code reconciled}. */
  public long reconciled() {
    return lines.stream()
        .filter(l -> l.match() != null && "reconciled".equals(l.match().reconciliation()))
        .count();
  }

  /** Lines with proposals still to confirm. */
  public long proposed() {
    return lines.stream()
        .filter(l -> l.status() != LineStatus.MATCHED)
        .filter(l -> l.status() != LineStatus.MISSING && l.status() != LineStatus.PROBLEM)
        .count();
  }

  /** Lines with no candidate, to be created. */
  public long missing() {
    return count(LineStatus.MISSING);
  }

  /** Extras that count against green — boundary extras excluded. */
  public long extra() {
    return extras.stream().filter(e -> !e.boundaryExtra()).count();
  }

  /** Extras labelled as probably belonging to a neighbouring statement. */
  public long boundary() {
    return extras.stream().filter(ExtraReview::boundaryExtra).count();
  }

  private long count(LineStatus status) {
    return lines.stream().filter(l -> l.status() == status).count();
  }
}
