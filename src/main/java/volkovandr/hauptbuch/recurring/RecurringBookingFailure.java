package volkovandr.hauptbuch.recurring;

import java.time.OffsetDateTime;

/**
 * A live template whose occurrences cannot book (data-model §14.3, recurring sub-plan slice f), as
 * the main page and the recurring page name it.
 *
 * @param recurringTemplateId the failing template, for the link to its editor
 * @param name the template's name
 * @param reason why the latest run could not book
 * @param since when the template first failed, kept across retries until a run completes
 */
public record RecurringBookingFailure(
    long recurringTemplateId, String name, String reason, OffsetDateTime since) {}
