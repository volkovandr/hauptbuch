package volkovandr.hauptbuch.receipts;

/**
 * The raw outcome of one successful Messages-API call (stage 9e): the model's verbatim TOON body
 * (stored in {@code parse_raw}, decoded downstream) and its usage block, recorded per parse and
 * used to compute the frozen {@code parse_cost} (data-model §13.1). "Successful" here means the
 * call completed and returned a body — the body may still be undecodable TOON, which seeding treats
 * as a failure while keeping the raw text.
 *
 * @param rawToon the model's verbatim response body
 * @param tokensIn input tokens billed
 * @param tokensOut output tokens billed
 * @param tokensCacheWrite cache-write tokens billed
 * @param tokensCacheRead cache-read tokens billed
 */
public record ReceiptParseResult(
    String rawToon, int tokensIn, int tokensOut, int tokensCacheWrite, int tokensCacheRead) {}
