package volkovandr.hauptbuch.recurring;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A recurring template's schedule (data-model §14.1): every {@code every} {@link CadenceUnit}s from
 * {@code startDate}, up to an optional inclusive {@code endDate}.
 *
 * <p>The k-th occurrence is always computed from the start, never from the previous occurrence, so
 * the anchor cannot drift. A monthly schedule repeats on the start date's day-of-month, or on the
 * month's last day where that day does not exist (31 Jan → 28 Feb → 31 Mar). A yearly schedule
 * started on 29 Feb falls back to 28 Feb in non-leap years. There is no business-day shifting.
 *
 * @param startDate the first occurrence
 * @param unit the cadence unit
 * @param every the cadence N, at least 1
 * @param endDate the last date an occurrence may fall on, or {@code null} for no end
 */
public record Schedule(LocalDate startDate, CadenceUnit unit, int every, LocalDate endDate) {

  /** Validate the cadence and the end date. */
  public Schedule {
    if (startDate == null || unit == null) {
      throw new IllegalArgumentException("A schedule needs a start date and a cadence unit");
    }
    if (every < 1) {
      throw new IllegalArgumentException("A cadence repeats every 1 or more " + unit.code() + "s");
    }
    if (endDate != null && endDate.isBefore(startDate)) {
      throw new IllegalArgumentException("The end date is before the start date");
    }
  }

  /**
   * The end date that "after {@code count} occurrences" stands for: the {@code count}-th
   * occurrence's date. The form offers the count as a convenience; only the date is stored.
   *
   * @throws IllegalArgumentException if {@code count} is below 1
   */
  public static LocalDate endDateAfter(
      LocalDate startDate, CadenceUnit unit, int every, int count) {
    if (count < 1) {
      throw new IllegalArgumentException("A schedule ends after 1 or more occurrences");
    }
    return new Schedule(startDate, unit, every, null).occurrence(count - 1);
  }

  /** The occurrences in the window {@code (from, to]}, capped at the end date, in date order. */
  public List<LocalDate> occurrencesBetween(LocalDate from, LocalDate to) {
    List<LocalDate> dates = new ArrayList<>();
    LocalDate last = endDate == null || to.isBefore(endDate) ? to : endDate;
    for (int index = 0; ; index++) {
      LocalDate date = occurrence(index);
      if (date.isAfter(last)) {
        return dates;
      }
      if (date.isAfter(from)) {
        dates.add(date);
      }
    }
  }

  /** Up to {@code count} occurrences strictly after {@code after}, capped at the end date. */
  public List<LocalDate> nextOccurrences(LocalDate after, int count) {
    List<LocalDate> dates = new ArrayList<>();
    for (int index = 0; dates.size() < count; index++) {
      LocalDate date = occurrence(index);
      if (endDate != null && date.isAfter(endDate)) {
        return dates;
      }
      if (date.isAfter(after)) {
        dates.add(date);
      }
    }
    return dates;
  }

  /** The occurrence at {@code index} (0 = the start date), always stepped from the start. */
  private LocalDate occurrence(int index) {
    long steps = (long) index * every;
    return switch (unit) {
      case DAY -> startDate.plusDays(steps);
      case WEEK -> startDate.plusWeeks(steps);
      case MONTH -> startDate.plusMonths(steps);
      case YEAR -> startDate.plusYears(steps);
    };
  }
}
