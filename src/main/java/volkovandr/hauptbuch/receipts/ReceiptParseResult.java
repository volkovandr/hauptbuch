package volkovandr.hauptbuch.receipts;

/**
 * The raw outcome of one successful Messages-API call (stage 9e): the model's verbatim TOON body
 * (stored in {@code parse_raw}, decoded downstream) and its usage block, recorded per parse and
 * used to compute the frozen {@code parse_cost} (data-model §13.1). "Successful" here means the
 * call completed and returned a body — the body may still be undecodable TOON, which seeding treats
 * as a failure while keeping the raw text.
 *
 * @param rawToon the current response body: the model's verbatim text, or its repaired / edited
 *     form (receipt-processing/34)
 * @param originalToon the model's verbatim body when {@code rawToon} was changed from it; null
 *     while {@code rawToon} is the model's own text
 * @param tokensIn input tokens billed
 * @param tokensOut output tokens billed
 * @param tokensCacheWrite cache-write tokens billed
 * @param tokensCacheRead cache-read tokens billed
 */
public record ReceiptParseResult(
    String rawToon,
    String originalToon,
    int tokensIn,
    int tokensOut,
    int tokensCacheWrite,
    int tokensCacheRead) {

  /** A result whose body is the model's own text, nothing edited. */
  public ReceiptParseResult(
      String rawToon, int tokensIn, int tokensOut, int tokensCacheWrite, int tokensCacheRead) {
    this(rawToon, null, tokensIn, tokensOut, tokensCacheWrite, tokensCacheRead);
  }

  /**
   * This result with its body replaced by {@code body} (an auto-repair or an operator edit): the
   * model's verbatim text is remembered in {@code originalToon} the first time the body changes,
   * and forgotten again if the body goes back to it (receipt-processing/34).
   */
  public ReceiptParseResult edited(String body) {
    String modelText = originalToon != null ? originalToon : rawToon;
    return new ReceiptParseResult(
        body,
        body.equals(modelText) ? null : modelText,
        tokensIn,
        tokensOut,
        tokensCacheWrite,
        tokensCacheRead);
  }
}
