package volkovandr.hauptbuch.recurring;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Parses and checks the template editor's schedule block (data-model §14.1): the raw text of a
 * {@link RecurringScheduleForm} into the schedule half of a {@link RecurringTemplateDraft}, or a
 * message for the editor to show. {@link RecurringTemplateService} fills in the entry half.
 */
final class RecurringScheduleParser {

  private RecurringScheduleParser() {}

  /**
   * The schedule part of a template's draft, parsed and checked; the entry part is still empty.
   *
   * @param start the split panel's Date field, which is the template's start
   * @throws IllegalArgumentException if the schedule is incomplete, carrying the message to show
   */
  static RecurringTemplateDraft parse(RecurringScheduleForm form, LocalDate start) {
    String name = blankToNull(form.name());
    if (name == null) {
      throw new IllegalArgumentException("A template needs a name");
    }
    if (start == null) {
      throw new IllegalArgumentException("A template needs a start date");
    }
    CadenceUnit unit = CadenceUnit.fromCode(form.cadenceUnit());
    int every = whole(form.cadenceN(), "The cadence", 1);
    // The schedule's own checks refuse an end date before the start.
    Schedule schedule = new Schedule(start, unit, every, endDate(form, start, unit, every));
    String confirmation = blankToNull(form.confirmation());
    return new RecurringTemplateDraft(
        name.strip(),
        schedule.startDate(),
        schedule.unit().code(),
        schedule.every(),
        schedule.endDate(),
        blankToNull(form.leadDays()) == null ? 0 : whole(form.leadDays(), "The lead time", 0),
        confirmation == null ? RecurringScheduleForm.AUTO : confirmation,
        false,
        null,
        managementUrl(form.managementUrl()),
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of());
  }

  private static LocalDate endDate(
      RecurringScheduleForm form, LocalDate start, CadenceUnit unit, int every) {
    String mode = blankToNull(form.endMode());
    if (RecurringScheduleForm.END_DATE.equals(mode)) {
      String text = blankToNull(form.endDate());
      if (text == null) {
        throw new IllegalArgumentException("Pick the end date, or choose no end");
      }
      try {
        return LocalDate.parse(text.strip());
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException("The end date is not a date", e);
      }
    }
    if (RecurringScheduleForm.END_AFTER.equals(mode)) {
      int count = whole(form.endAfter(), "The number of occurrences", 1);
      return Schedule.endDateAfter(start, unit, every, count);
    }
    return null;
  }

  /** A whole number of at least {@code min}, or a message naming {@code what}. */
  private static int whole(String text, String what, int min) {
    int value;
    try {
      value = Integer.parseInt(text == null ? "" : text.strip());
    } catch (NumberFormatException e) {
      value = min - 1;
    }
    if (value < min) {
      throw new IllegalArgumentException(what + " must be a whole number of at least " + min);
    }
    return value;
  }

  /**
   * The management link, which opens in a new tab: only a web address is accepted, so a stored
   * value can never be a {@code javascript:} link.
   */
  private static String managementUrl(String text) {
    String url = blankToNull(text);
    if (url == null) {
      return null;
    }
    String stripped = url.strip();
    String lower = stripped.toLowerCase(Locale.ROOT);
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
      throw new IllegalArgumentException("The management link must start with https:// or http://");
    }
    return stripped;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
