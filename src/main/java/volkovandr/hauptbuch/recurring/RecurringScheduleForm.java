package volkovandr.hauptbuch.recurring;

import org.springframework.util.MultiValueMap;

/**
 * The template editor's schedule block as submitted (data-model §14.1): everything a template adds
 * to the split panel's entry. The start date is not here; it is the panel's own Date field,
 * labelled Start in template mode. Every field is the raw text, so a refused save redisplays
 * exactly what was typed; {@link RecurringTemplateService} parses and validates it.
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
    String managementUrl) {

  static final String END_NONE = "none";
  static final String END_DATE = "date";
  static final String END_AFTER = "after";
  static final String AUTO = "auto";
  static final String REVIEW = "review";

  /** A blank schedule for a new template: every month, no end, booked on the day, automatic. */
  static RecurringScheduleForm blank() {
    return new RecurringScheduleForm(
        null, "", "1", CadenceUnit.MONTH.code(), END_NONE, "", "", "0", AUTO, "");
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
        params.getFirst("managementUrl"));
  }
}
