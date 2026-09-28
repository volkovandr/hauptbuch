package volkovandr.hauptbuch.recurring;

import java.time.LocalDate;
import java.util.List;

/**
 * A recurring template as written by a save (data-model §14.1): the schedule and settings, the
 * split panel's header fields, the header tags, and the lines. The cursor ({@code booked_through})
 * is not part of it; the booking run owns that.
 *
 * <p>The entry is always stored in the split shape. A simple dock entry is a one-line split whose
 * line carries the header tags, which is how the simple dock's "tags on every leg" is reproduced.
 *
 * @param name the operator's name for the template
 * @param startDate the first occurrence
 * @param cadenceUnit a {@link CadenceUnit#code()}
 * @param cadenceN the cadence N, at least 1
 * @param endDate the inclusive last occurrence date, or null for no end
 * @param leadDays how many days ahead of its date an occurrence is booked
 * @param confirmation {@code auto} or {@code review}
 * @param endReminder whether the main page reminds of the end date
 * @param endReminderDays how many days before the end date the reminder starts, or null
 * @param managementUrl the provider's management page, or null
 * @param accountId the funding account, or null when a person funds the entry
 * @param personId the funding person, or null when an account funds the entry
 * @param fundingPersonDirection {@code FOR}/{@code BY} alongside {@code personId}, else null
 * @param payeeId the payee, or null
 * @param note the transaction-level note, or null
 * @param spendingCurrencyCode the one currency the lines are in; null means the funding currency
 * @param tagIds the header tags, which land on the funding leg; never null
 * @param lines the split lines in order; never null
 */
public record RecurringTemplateDraft(
    String name,
    LocalDate startDate,
    String cadenceUnit,
    int cadenceN,
    LocalDate endDate,
    int leadDays,
    String confirmation,
    boolean endReminder,
    Integer endReminderDays,
    String managementUrl,
    Long accountId,
    Long personId,
    String fundingPersonDirection,
    Long payeeId,
    String note,
    String spendingCurrencyCode,
    List<Long> tagIds,
    List<RecurringTemplateLineDraft> lines) {

  /** Defensively copy the tag ids and lines (the house pattern for record lists). */
  public RecurringTemplateDraft {
    tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
    lines = lines == null ? List.of() : List.copyOf(lines);
  }

  /** The draft's schedule, for the occurrence math. */
  public Schedule schedule() {
    return new Schedule(startDate, CadenceUnit.fromCode(cadenceUnit), cadenceN, endDate);
  }
}
