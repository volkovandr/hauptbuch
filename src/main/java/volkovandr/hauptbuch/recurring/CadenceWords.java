package volkovandr.hauptbuch.recurring;

import java.time.format.TextStyle;
import java.util.Locale;

/**
 * A schedule's cadence in words, for the recurring page (recurring sub-plan slice b): "every day",
 * "every 2 weeks on Friday", "every 2 months on the 31st", "every year on 29 February". The anchor
 * (weekday, day of month, day and month) comes from the start date. UI copy is English (CLAUDE.md
 * §5).
 */
final class CadenceWords {

  private static final int TEENS_FIRST = 11;
  private static final int TEENS_LAST = 13;
  private static final int DECIMAL = 10;

  private CadenceWords() {}

  /** The cadence of {@code schedule} in words. */
  static String of(Schedule schedule) {
    String every = every(schedule.unit(), schedule.every());
    return switch (schedule.unit()) {
      case DAY -> every;
      case WEEK ->
          every
              + " on "
              + schedule.startDate().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
      case MONTH -> every + " on the " + ordinal(schedule.startDate().getDayOfMonth());
      case YEAR ->
          every
              + " on "
              + schedule.startDate().getDayOfMonth()
              + " "
              + schedule.startDate().getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    };
  }

  private static String every(CadenceUnit unit, int count) {
    String word =
        switch (unit) {
          case DAY -> "day";
          case WEEK -> "week";
          case MONTH -> "month";
          case YEAR -> "year";
        };
    return count == 1 ? "every " + word : "every " + count + " " + word + "s";
  }

  private static String ordinal(int day) {
    if (day >= TEENS_FIRST && day <= TEENS_LAST) {
      return day + "th";
    }
    return switch (day % DECIMAL) {
      case 1 -> day + "st";
      case 2 -> day + "nd";
      case 3 -> day + "rd";
      default -> day + "th";
    };
  }
}
