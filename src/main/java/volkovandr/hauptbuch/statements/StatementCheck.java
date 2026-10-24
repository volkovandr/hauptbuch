package volkovandr.hauptbuch.statements;

import java.util.List;

/**
 * The verdict of the green rule (statements.md §6.5) on one statement: what stops it being green.
 * Green is the absence of problems, so the verdict and its reason cannot disagree.
 *
 * @param problems what is wrong, in reading order; empty when the statement is green
 */
public record StatementCheck(List<String> problems) {

  /** Defensive copy of the list (the house pattern for record lists). */
  public StatementCheck {
    problems = List.copyOf(problems);
  }

  /** Whether the statement is green. */
  public boolean green() {
    return problems.isEmpty();
  }

  /** The problems as one line for the list; empty when green. */
  public String reason() {
    return String.join("; ", problems);
  }
}
