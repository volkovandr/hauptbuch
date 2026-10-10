package volkovandr.hauptbuch.statements;

/**
 * The statement-parsing seam (statements.md §3.2): one synchronous Messages-API call that hands the
 * model the operator-edited statement text plus parsing instructions and returns its raw TOON body
 * and usage. {@code AnthropicStatementParser} wraps the official SDK; tests drive a fake so the
 * suites never touch the network.
 */
@FunctionalInterface
interface StatementParser {

  /**
   * Parse one statement's text.
   *
   * @return the raw body and token usage of a completed call
   * @throws StatementParseException when the call could not complete
   */
  StatementParseResult parse(StatementParseRequest request);
}
