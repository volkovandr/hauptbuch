package volkovandr.hauptbuch.statements.repository;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The billed token counts and the frozen cost of one parse, as stored on the statement. The cost is
 * computed once at parse time from the {@code settings} price rates and never recomputed.
 *
 * @param tokensIn input tokens billed
 * @param tokensOut output tokens billed
 * @param tokensCacheWrite cache-write tokens billed
 * @param tokensCacheRead cache-read tokens billed
 * @param cost the frozen USD cost
 */
public record ParseUsage(
    int tokensIn, int tokensOut, int tokensCacheWrite, int tokensCacheRead, BigDecimal cost) {

  /** The named parameters the statement update statements share. */
  Map<String, Object> asParams() {
    return Map.of(
        "tokensIn", tokensIn,
        "tokensOut", tokensOut,
        "tokensCacheWrite", tokensCacheWrite,
        "tokensCacheRead", tokensCacheRead,
        "parseCost", cost);
  }
}
