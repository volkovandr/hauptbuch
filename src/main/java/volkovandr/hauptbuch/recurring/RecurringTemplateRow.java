package volkovandr.hauptbuch.recurring;

import java.time.LocalDate;
import java.util.List;

/**
 * One row of the recurring page's template list (recurring sub-plan slice b).
 *
 * @param recurringTemplateId the template's id, for its edit link
 * @param name the operator's name for the template
 * @param cadence the cadence in words ({@link CadenceWords})
 * @param amount the net amount off the funding account per occurrence, signed as the register shows
 *     it (negative = you pay), formatted for display
 * @param account the funding account's label, or the funding person as {@code for}/{@code by}
 * @param confirmation {@code Automatic} or {@code Review}
 * @param leadTime when an occurrence is booked, e.g. {@code on the day} or {@code 3 days ahead}
 * @param nextDates the next three occurrence dates from today, fewer near the end date
 * @param managementUrl the provider's management page, or null
 */
public record RecurringTemplateRow(
    long recurringTemplateId,
    String name,
    String cadence,
    String amount,
    String account,
    String confirmation,
    String leadTime,
    List<LocalDate> nextDates,
    String managementUrl) {

  /** Defensively copy the dates. */
  public RecurringTemplateRow {
    nextDates = List.copyOf(nextDates);
  }
}
