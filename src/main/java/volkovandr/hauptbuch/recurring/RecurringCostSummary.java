package volkovandr.hauptbuch.recurring;

import java.util.List;

/**
 * The recurring page's Recurring cost summary (data-model §14.4, recurring sub-plan slice g): the
 * live templates added up per month and per year in base at today's rate. Each non-funding leg is
 * an expense (broken down by top-level category), income, or a transfer to an own account or a
 * person; the net is income − expenses − transfers. It is schedule math, not an engine Report.
 *
 * @param expenses one line per top-level expense category, by name
 * @param expenseTotal the expenses added up
 * @param income the income legs
 * @param transfer the transfer and person legs, outflows positive
 * @param net income − expenses − transfers
 * @param leftOut the templates left out for want of a rate for their currency, by name
 */
public record RecurringCostSummary(
    List<Line> expenses,
    Line expenseTotal,
    Line income,
    Line transfer,
    Line net,
    List<String> leftOut) {

  /** Defensively copy the lists. */
  public RecurringCostSummary {
    expenses = List.copyOf(expenses);
    leftOut = List.copyOf(leftOut);
  }

  /**
   * One row of the summary, formatted in base.
   *
   * @param label the category name, or the row's name
   * @param perMonth the amount per month
   * @param perYear the amount per year
   */
  public record Line(String label, String perMonth, String perYear) {}
}
