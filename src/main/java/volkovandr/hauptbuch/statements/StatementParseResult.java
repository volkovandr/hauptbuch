package volkovandr.hauptbuch.statements;

/**
 * The raw outcome of one completed call: the model's verbatim body and its four billed token
 * counts. The body may still be undecodable TOON; that is the caller's to detect.
 *
 * @param rawToon the model's verbatim response body
 * @param tokensIn input tokens billed
 * @param tokensOut output tokens billed
 * @param tokensCacheWrite cache-write tokens billed
 * @param tokensCacheRead cache-read tokens billed
 */
record StatementParseResult(
    String rawToon, int tokensIn, int tokensOut, int tokensCacheWrite, int tokensCacheRead) {}
