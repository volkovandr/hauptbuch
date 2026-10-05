package volkovandr.hauptbuch.statements;

/**
 * A single row or typed value that cannot be read; the message is what the operator sees — on the
 * line as its problem, or in front of the grid row they typed it in.
 */
final class RowProblem extends RuntimeException {

  private static final long serialVersionUID = 1L;

  RowProblem(String message) {
    super(message);
  }

  RowProblem(String message, Throwable cause) {
    super(message, cause);
  }
}
