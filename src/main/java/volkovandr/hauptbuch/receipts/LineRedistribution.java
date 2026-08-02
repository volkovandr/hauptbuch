package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pure per-line redistribute arithmetic of the post-process split toolkit (plan §9f): "spread
 * this line over the others, remove it". The removed line's amount is distributed across every
 * <em>other</em> line except real-account transfer legs — beneficiary lines absorb (their items
 * bore the tax too) and negative lines participate with negative shares — proportionally to each
 * absorbing line's amount, with a cent-level largest-remainder allotment so the receipt total is
 * preserved exactly. It is a deliberate alternative to any VAT-detection heuristic: two VAT lines
 * are two clicks, and it works for Pfand/rounding lines just the same.
 *
 * <p>Pure over its inputs (no ledger reads), so it is unit-tested directly; the surface converts
 * the form's line state into {@link Line}s (an amount and whether the line resolved to a {@code To
 * →}/{@code From ←} real-account transfer) and applies the result back onto the surviving lines.
 */
public final class LineRedistribution {

  /** German entry is to the minor unit; two places (cents) is the largest-remainder grid. */
  private static final int MINOR = 2;

  private LineRedistribution() {}

  /**
   * Spread the line at {@code targetIndex} across the absorbing lines and drop it. Returns the new
   * amounts of the <em>surviving</em> lines, in their original order (the target removed): an
   * absorbing line carries its own amount plus its share; a transfer leg is returned unchanged.
   *
   * @throws RedistributeRefusedException when there is nothing to absorb the amount — no non-
   *     transfer line remains, or the absorbing lines' amounts sum to zero (no proportional base)
   */
  public static List<BigDecimal> spread(List<Line> lines, int targetIndex) {
    BigInteger spread = cents(lines.get(targetIndex).amount());
    Absorbers absorbers = absorbers(lines, targetIndex);
    if (absorbers.base().signum() == 0) {
      throw new RedistributeRefusedException(
          "There are no lines to absorb this amount (their amounts sum to zero).");
    }

    List<BigInteger> shares = largestRemainder(spread, absorbers.weights(), absorbers.base());
    return survivors(lines, targetIndex, absorbers.indexes(), shares);
  }

  /** The absorbing lines (every other line that is not a real-account transfer leg). */
  private static Absorbers absorbers(List<Line> lines, int targetIndex) {
    List<Integer> indexes = new ArrayList<>();
    List<BigInteger> weights = new ArrayList<>();
    BigInteger base = BigInteger.ZERO;
    for (int i = 0; i < lines.size(); i++) {
      if (i == targetIndex || lines.get(i).transferLeg()) {
        continue;
      }
      BigInteger weight = cents(lines.get(i).amount());
      indexes.add(i);
      weights.add(weight);
      base = base.add(weight);
    }
    return new Absorbers(indexes, weights, base);
  }

  /**
   * The surviving lines in original order (the target removed): an absorbing line carries its own
   * amount plus its share; a transfer leg is passed through unchanged.
   */
  private static List<BigDecimal> survivors(
      List<Line> lines, int targetIndex, List<Integer> absorberIndexes, List<BigInteger> shares) {
    List<BigDecimal> survivors = new ArrayList<>();
    int absorber = 0;
    for (int i = 0; i < lines.size(); i++) {
      if (i == targetIndex) {
        continue;
      }
      if (absorber < absorberIndexes.size() && absorberIndexes.get(absorber) == i) {
        survivors.add(amount(cents(lines.get(i).amount()).add(shares.get(absorber))));
        absorber++;
      } else {
        survivors.add(lines.get(i).amount()); // an untouched transfer leg
      }
    }
    return survivors;
  }

  /**
   * Allot {@code spread} cents across the weights in proportion to each, exactly, by the
   * largest-remainder (Hamilton) method: floor each ideal share toward −∞, then hand the few
   * leftover cents to the largest fractional remainders (ties to the lowest index). Flooring toward
   * −∞ keeps the leftover count in {@code [0, n)} even when weights (and the base) are negative, so
   * the shares always sum back to {@code spread}.
   */
  private static List<BigInteger> largestRemainder(
      BigInteger spread, List<BigInteger> weights, BigInteger base) {
    int n = weights.size();
    BigInteger[] floors = new BigInteger[n];
    BigInteger[] remainders = new BigInteger[n];
    BigInteger allocated = BigInteger.ZERO;
    for (int i = 0; i < n; i++) {
      BigInteger numerator = spread.multiply(weights.get(i));
      BigInteger[] qr = numerator.divideAndRemainder(base);
      BigInteger quotient = qr[0];
      BigInteger remainder = qr[1];
      // Adjust truncation-toward-zero to floor-toward-−∞ so the remainder is 0 ≤ r/base < 1.
      if (remainder.signum() != 0 && remainder.signum() != base.signum()) {
        quotient = quotient.subtract(BigInteger.ONE);
        remainder = remainder.add(base);
      }
      floors[i] = quotient;
      remainders[i] = remainder;
      allocated = allocated.add(quotient);
    }

    int leftover = spread.subtract(allocated).intValueExact();
    // Rank absorbers by the fractional remainder r/base (descending); base is shared, so
    // multiplying
    // the remainder by its sign compares the fractions directly. Lowest index wins ties.
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      order.add(i);
    }
    int baseSign = base.signum();
    order.sort(
        Comparator.<Integer, BigInteger>comparing(
                i -> remainders[i].multiply(BigInteger.valueOf(baseSign)))
            .reversed()
            .thenComparing(Comparator.naturalOrder()));

    List<BigInteger> shares = new ArrayList<>(List.of(floors));
    for (int k = 0; k < leftover; k++) {
      int i = order.get(k);
      shares.set(i, shares.get(i).add(BigInteger.ONE));
    }
    return shares;
  }

  private static BigInteger cents(BigDecimal amount) {
    return amount
        .movePointRight(MINOR)
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .toBigIntegerExact();
  }

  private static BigDecimal amount(BigInteger cents) {
    return new BigDecimal(cents).movePointLeft(MINOR);
  }

  /**
   * One line as the redistribute sees it: its amount and whether it resolved to a real-account
   * transfer ({@code To →}/{@code From ←}) — the one kind of line that never absorbs.
   */
  public record Line(BigDecimal amount, boolean transferLeg) {}

  /** The gathered absorbing lines: their original indexes, cent weights, and weight sum (base). */
  private record Absorbers(List<Integer> indexes, List<BigInteger> weights, BigInteger base) {}

  /** Signals that a redistribute cannot proceed because nothing can absorb the amount. */
  public static class RedistributeRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Create the exception with a user-facing message the surface shows verbatim. */
    public RedistributeRefusedException(String message) {
      super(message);
    }
  }
}
