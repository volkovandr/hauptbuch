package volkovandr.hauptbuch.recurring;

import org.springframework.util.MultiValueMap;

/**
 * The template editor's schedule block as submitted (data-model §14.1): everything a template adds
 * to the split panel's entry. The start date is not here; it is the panel's own Date field,
 * labelled Start in template mode. Every field is the raw text, so a refused save redisplays
 * exactly what was typed; {@link RecurringScheduleParser} parses and validates it.
 *
 * @param recurringTemplateId the template being edited, or null for a new one
 * @param name the operator's name for the template
 * @param cadenceN the cadence N
 * @param cadenceUnit a {@link CadenceUnit#code()}
 * @param endMode {@code none}, {@code date} or {@code after}
 * @param endDate the end date when {@code endMode} is {@code date}
 * @param endAfter the occurrence count K when {@code endMode} is {@code after}
 * @param leadDays how many days ahead of its date an occurrence is booked
 * @param confirmation {@code auto} or {@code review}
 * @param managementUrl the provider's management page, or blank
 * @param pastOccurrences a new template's answer to "book the past occurrences?" (data-model
 *     §14.3): {@code book} or {@code skip}, blank while unasked
 * @param pendingRows an existing template's answer for the pending rows a new end date cuts off
 *     (data-model §14.3): a {@link PendingRows#code()}, blank while unasked
 * @param endReminder {@code true} when the main page should remind of the end date, else blank
 * @param endReminderDays how many days before the end date the reminder starts
 */
public record RecurringScheduleForm(
    Long recurringTemplateId,
    String name,
    String cadenceN,
    String cadenceUnit,
    String endMode,
    String endDate,
    String endAfter,
    String leadDays,
    String confirmation,
    String managementUrl,
    String pastOccurrences,
    String pendingRows,
    String endReminder,
    String endReminderDays) {

  static final String END_NONE = "none";
  static final String END_DATE = "date";
  static final String END_AFTER = "after";
  static final String AUTO = "auto";
  static final String REVIEW = "review";
  static final String BOOK_PAST = "book";
  static final String SKIP_PAST = "skip";
  static final String DEFAULT_REMINDER_DAYS = "30";
  static final String TICKED = "true";

  /** Whether the operator has answered the past-occurrences question (either way). */
  boolean pastOccurrencesAnswered() {
    return pastOccurrences != null && !pastOccurrences.isBlank();
  }

  /**
   * The answer for pending rows the end date cuts off, or null while unasked.
   *
   * @throws IllegalArgumentException if the value names no answer
   */
  PendingRows pendingRowsAnswer() {
    return PendingRows.fromCode(pendingRows);
  }

  /** Whether the operator ticked the end reminder. */
  public boolean endReminderTicked() {
    return TICKED.equalsIgnoreCase(endReminder);
  }

  /**
   * A blank schedule for a new template: every month, no end, booked on the day, automatic. An end
   * reminder, once an end is picked, starts {@value #DEFAULT_REMINDER_DAYS} days ahead.
   */
  static RecurringScheduleForm blank() {
    return new RecurringScheduleForm(
        null,
        "",
        "1",
        CadenceUnit.MONTH.code(),
        END_NONE,
        "",
        "",
        "0",
        AUTO,
        "",
        "",
        "",
        "",
        DEFAULT_REMINDER_DAYS);
  }

  /** Bind the schedule block from the editor's raw request parameters. */
  static RecurringScheduleForm bind(MultiValueMap<String, String> params) {
    String id = params.getFirst("recurringTemplateId");
    return new RecurringScheduleForm(
        id == null || id.isBlank() ? null : Long.valueOf(id.strip()),
        params.getFirst("name"),
        params.getFirst("cadenceN"),
        params.getFirst("cadenceUnit"),
        params.getFirst("endMode"),
        params.getFirst("endDate"),
        params.getFirst("endAfter"),
        params.getFirst("leadDays"),
        params.getFirst("confirmation"),
        params.getFirst("managementUrl"),
        params.getFirst("pastOccurrences"),
        params.getFirst("pendingRows"),
        params.getFirst("endReminder"),
        params.getFirst("endReminderDays"));
  }
}
