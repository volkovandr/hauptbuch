package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import volkovandr.hauptbuch.recurring.RecurringCostSummary.Line;

/**
 * The recurring page's Transfers table (data-model §14.4): money the live templates move between
 * the book's own accounts, one row per "source → destination" pair, always written in the direction
 * the money moves. The total is the funds moved; it is no part of Net, which a transfer leaves
 * unchanged.
 */
final class TransferTable {

  private final Map<String, Flow> flows = new TreeMap<>();

  /** One pair's per-year amount, in its template's currency. */
  private static final class Flow {

    private final String label;
    private final Valuation valuation;
    private BigDecimal perYear = BigDecimal.ZERO;

    Flow(String label, Valuation valuation) {
      this.label = label;
      this.valuation = valuation;
    }
  }

  /**
   * Adds a transfer of {@code perYear} from {@code source} to {@code destination}; a negative
   * amount moves the other way.
   */
  void add(String source, String destination, BigDecimal perYear, Valuation valuation) {
    String label =
        perYear.signum() < 0 ? destination + " → " + source : source + " → " + destination;
    Flow flow =
        flows.computeIfAbsent(
            label + " " + valuation.currency(), key -> new Flow(label, valuation));
    flow.perYear = flow.perYear.add(perYear.abs());
  }

  /** One line per pair, by label. */
  List<Line> lines() {
    return flows.values().stream()
        .map(flow -> flow.valuation.line(flow.label, 0, null, flow.perYear))
        .toList();
  }

  /** The funds moved, in base. */
  Line total(String base) {
    BigDecimal total = BigDecimal.ZERO;
    for (Flow flow : flows.values()) {
      total = total.add(flow.valuation.toBase(flow.perYear));
    }
    return Valuation.inBase(base).line("Transfers", 0, null, total);
  }
}
