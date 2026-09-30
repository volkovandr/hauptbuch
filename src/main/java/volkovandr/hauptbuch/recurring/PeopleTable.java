package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import volkovandr.hauptbuch.recurring.RecurringCostSummary.Line;

/**
 * The recurring page's People table (data-model §14.4): how the live templates move each person's
 * debt, in the people screen's sign (positive: they owe you more). One row per person and currency,
 * its subtotal saying who owes whom, then that person's templates; the total says whether the book
 * is owed more or owes more overall. A template between two people appears under each, netting to
 * zero in the total.
 */
final class PeopleTable {

  private final Map<String, Debt> debts = new TreeMap<>();

  /** One person's debt in one currency, and each template's share of it, per year. */
  private static final class Debt {

    private final String name;
    private final Valuation valuation;
    private final Map<String, BigDecimal> templates = new TreeMap<>();
    private BigDecimal perYear = BigDecimal.ZERO;

    Debt(String name, Valuation valuation) {
      this.name = name;
      this.valuation = valuation;
    }
  }

  /**
   * Adds {@code perYear} to the debt of {@code personName} in the valuation's currency through
   * {@code templateName}: positive, they owe you more; negative, you owe them more.
   */
  void add(String personName, String templateName, BigDecimal perYear, Valuation valuation) {
    String label = personName + " (" + valuation.currency() + ")";
    Debt debt = debts.computeIfAbsent(label, key -> new Debt(personName, valuation));
    debt.perYear = debt.perYear.add(perYear);
    debt.templates.merge(templateName, perYear, BigDecimal::add);
  }

  /** Each person's subtotal, then their templates by name. */
  List<Line> lines() {
    List<Line> lines = new ArrayList<>();
    debts.forEach(
        (label, debt) -> {
          lines.add(debt.valuation.line(label, 0, personNote(debt), debt.perYear));
          debt.templates.forEach(
              (template, perYear) -> lines.add(debt.valuation.line(template, 1, null, perYear)));
        });
    return lines;
  }

  /** Every person's debt added up, in base. */
  Line total(String base) {
    BigDecimal total = BigDecimal.ZERO;
    for (Debt debt : debts.values()) {
      total = total.add(debt.valuation.toBase(debt.perYear));
    }
    String note =
        switch (total.signum()) {
          case 1 -> "you are owed more";
          case -1 -> "you owe more";
          default -> null;
        };
    return Valuation.inBase(base).line("People", 0, note, total);
  }

  private static String personNote(Debt debt) {
    return switch (debt.perYear.signum()) {
      case 1 -> debt.name + " owes you more";
      case -1 -> "you owe " + debt.name + " more";
      default -> null;
    };
  }
}
