package volkovandr.hauptbuch.recurring;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A stored recurring template's header row (data-model §14.1/§14.2): its schedule, its settings and
 * the split panel's header fields. The lines are {@link RecurringTemplateLine}s; the header tags
 * and each line's tags live in their own junction tables.
 *
 * @param recurringTemplateId surrogate PK
 * @param name the operator's name for the template
 * @param startDate the first occurrence
 * @param cadenceUnit the stored {@link CadenceUnit#code()}
 * @param cadenceN the cadence N, at least 1
 * @param endDate the inclusive last occurrence date, or null for no end
 * @param leadDays how many days ahead of its date an occurrence is booked (0 = on the day)
 * @param confirmation {@code auto} (books confirmed) or {@code review} (books pending_review)
 * @param bookedThrough the cursor: the latest occurrence date the template has handled (§14.3)
 * @param endReminder whether the main page reminds of the end date
 * @param endReminderDays how many days before the end date the reminder starts, or null
 * @param managementUrl the provider's management page, or null
 * @param accountId the funding account, or null when a person funds the entry
 * @param personId the funding person, or null when an account funds the entry
 * @param fundingPersonDirection {@code FOR}/{@code BY} alongside {@code personId}, else null
 * @param payeeId the payee, or null
 * @param note the transaction-level note, or null
 * @param spendingCurrencyCode the one currency the lines are in; null means the funding currency
 * @param createdAt when the template was created
 * @param updatedAt when the template was last saved
 * @param deletedAt the soft-delete timestamp, or null while live
 */
public record RecurringTemplate(
    long recurringTemplateId,
    String name,
    LocalDate startDate,
    String cadenceUnit,
    int cadenceN,
    LocalDate endDate,
    int leadDays,
    String confirmation,
    LocalDate bookedThrough,
    boolean endReminder,
    Integer endReminderDays,
    String managementUrl,
    Long accountId,
    Long personId,
    String fundingPersonDirection,
    Long payeeId,
    String note,
    String spendingCurrencyCode,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime deletedAt) {

  /** The template's schedule, for the occurrence math. */
  public Schedule schedule() {
    return new Schedule(startDate, CadenceUnit.fromCode(cadenceUnit), cadenceN, endDate);
  }
}
