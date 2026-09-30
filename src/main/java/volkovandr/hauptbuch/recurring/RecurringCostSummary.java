package volkovandr.hauptbuch.recurring;

import java.util.ArrayList;
import java.util.List;

/**
 * The recurring page's Recurring cost summary (data-model §14.4, recurring sub-plan slice g): the
 * live templates that have not ended, per month and per year, as three tables. The Recurring cost
 * table is expenses and income in base, each a total over the category hierarchy the templates
 * touch with a subtotal on every level, and Net = income − expenses. The Transfers table lists the
 * money moved between own accounts, and the People table how each person's debt moves (positive:
 * they owe you more); neither is part of Net. It is schedule math, not an engine Report.
 *
 * @param expenses one line per expense category a template touches, depth first and by name within
 *     a level, each a subtotal of the categories under it
 * @param expenseTotal the expenses added up
 * @param income one line per income category a template touches, as the expenses
 * @param incomeTotal the income added up
 * @param net income − expenses
 * @param transfers one "source → destination" line per pair of own accounts, in its currency
 * @param transferTotal the funds moved, in base
 * @param people per person and currency a subtotal saying who owes whom, then its templates
 * @param peopleTotal every person's debt change added up in base, saying which way it goes
 * @param leftOut the templates left out for want of a rate for their currency, by name
 */
public record RecurringCostSummary(
    List<Line> expenses,
    Line expenseTotal,
    List<Line> income,
    Line incomeTotal,
    Line net,
    List<Line> transfers,
    Line transferTotal,
    List<Line> people,
    Line peopleTotal,
    List<String> leftOut) {

  /** Defensively copy the lists. */
  public RecurringCostSummary {
    expenses = List.copyOf(expenses);
    income = List.copyOf(income);
    transfers = List.copyOf(transfers);
    people = List.copyOf(people);
    leftOut = List.copyOf(leftOut);
  }

  /** The Recurring cost table's rows: each section's total followed by its breakdown. */
  public List<Line> rows() {
    List<Line> rows = new ArrayList<>();
    rows.add(expenseTotal);
    rows.addAll(expenses);
    rows.add(incomeTotal);
    rows.addAll(income);
    return rows;
  }

  /**
   * One row of the summary, formatted in its currency with base in parentheses where different.
   *
   * @param label the category name, "source → destination", the person, the template, or the
   *     section's name
   * @param depth the level under the section's total or person (1 = top level); 0 for a total, Net,
   *     a transfer, or a person
   * @param note who owes whom, on a person's row and the People total; null elsewhere
   * @param perMonth the amount per month
   * @param perYear the amount per year
   */
  public record Line(String label, int depth, String note, String perMonth, String perYear) {}
}
