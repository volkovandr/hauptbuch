package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): the schedule math of a recurring template (data-model §14.1) — every N
 * days / weeks / months / years from the start date, the month-end clamp that never drifts, the 29
 * February fallback, the inclusive end date, and "after K occurrences" stored as the K-th date.
 */
class ScheduleTest {

  private static LocalDate date(int year, int month, int day) {
    return LocalDate.of(year, month, day);
  }

  private static Schedule open(LocalDate start, CadenceUnit unit, int every) {
    return new Schedule(start, unit, every, null);
  }

  // ── days and weeks ──────────────────────────────────────────────────────────

  @Test
  void everyDayStepsOneDay() {
    Schedule daily = open(date(2026, 2, 27), CadenceUnit.DAY, 1);

    assertThat(daily.nextOccurrences(date(2026, 2, 26), 3))
        .containsExactly(date(2026, 2, 27), date(2026, 2, 28), date(2026, 3, 1));
  }

  @Test
  void everyTenDaysStepsTenDays() {
    Schedule schedule = open(date(2026, 1, 1), CadenceUnit.DAY, 10);

    assertThat(schedule.nextOccurrences(date(2025, 12, 31), 3))
        .containsExactly(date(2026, 1, 1), date(2026, 1, 11), date(2026, 1, 21));
  }

  @Test
  void everyWeekStepsSevenDays() {
    Schedule weekly = open(date(2026, 9, 28), CadenceUnit.WEEK, 1);

    assertThat(weekly.nextOccurrences(date(2026, 9, 27), 3))
        .containsExactly(date(2026, 9, 28), date(2026, 10, 5), date(2026, 10, 12));
  }

  @Test
  void everyTwoWeeksStepsFourteenDays() {
    Schedule fortnightly = open(date(2026, 9, 28), CadenceUnit.WEEK, 2);

    assertThat(fortnightly.nextOccurrences(date(2026, 9, 27), 3))
        .containsExactly(date(2026, 9, 28), date(2026, 10, 12), date(2026, 10, 26));
  }

  // ── months ──────────────────────────────────────────────────────────────────

  @Test
  void monthlyOnThe31stClampsToMonthEndWithoutDrift() {
    Schedule monthly = open(date(2026, 1, 31), CadenceUnit.MONTH, 1);

    assertThat(monthly.nextOccurrences(date(2026, 1, 30), 5))
        .containsExactly(
            date(2026, 1, 31),
            date(2026, 2, 28),
            date(2026, 3, 31),
            date(2026, 4, 30),
            date(2026, 5, 31));
  }

  @Test
  void monthlyOnThe31stLandsOnTheLeapDayInLeapYears() {
    Schedule monthly = open(date(2028, 1, 31), CadenceUnit.MONTH, 1);

    assertThat(monthly.nextOccurrences(date(2028, 1, 31), 2))
        .containsExactly(date(2028, 2, 29), date(2028, 3, 31));
  }

  @Test
  void monthlyOnThe30thKeepsThe30thAfterFebruary() {
    Schedule monthly = open(date(2026, 1, 30), CadenceUnit.MONTH, 1);

    assertThat(monthly.nextOccurrences(date(2026, 1, 30), 3))
        .containsExactly(date(2026, 2, 28), date(2026, 3, 30), date(2026, 4, 30));
  }

  @Test
  void monthlyFromA30DayMonthEndSticksToMonthEnd() {
    Schedule monthly = open(date(2026, 9, 30), CadenceUnit.MONTH, 1);

    assertThat(monthly.nextOccurrences(date(2026, 9, 29), 5))
        .containsExactly(
            date(2026, 9, 30),
            date(2026, 10, 31),
            date(2026, 11, 30),
            date(2026, 12, 31),
            date(2027, 1, 31));
  }

  @Test
  void monthlyFromNonLeapFebruaryEndSticksToMonthEnd() {
    Schedule monthly = open(date(2026, 2, 28), CadenceUnit.MONTH, 1);

    assertThat(monthly.nextOccurrences(date(2026, 2, 27), 4))
        .containsExactly(
            date(2026, 2, 28), date(2026, 3, 31), date(2026, 4, 30), date(2026, 5, 31));
  }

  @Test
  void monthlyFromLeapFebruaryEndSticksToMonthEnd() {
    Schedule monthly = open(date(2028, 2, 29), CadenceUnit.MONTH, 1);

    assertThat(monthly.nextOccurrences(date(2028, 2, 29), 2))
        .containsExactly(date(2028, 3, 31), date(2028, 4, 30));
  }

  @Test
  void monthEndStartReachesFebruaryOnItsLastDay() {
    Schedule monthly = open(date(2026, 9, 30), CadenceUnit.MONTH, 1);

    assertThat(monthly.occurrencesBetween(date(2027, 1, 31), date(2027, 3, 31)))
        .containsExactly(date(2027, 2, 28), date(2027, 3, 31));
  }

  @Test
  void everyTwoAndThreeMonthsFromMonthEndStartStickToMonthEnd() {
    assertThat(open(date(2026, 9, 30), CadenceUnit.MONTH, 2).nextOccurrences(date(2026, 9, 30), 3))
        .containsExactly(date(2026, 11, 30), date(2027, 1, 31), date(2027, 3, 31));
    assertThat(
            open(date(2026, 11, 30), CadenceUnit.MONTH, 3).nextOccurrences(date(2026, 11, 30), 3))
        .containsExactly(date(2027, 2, 28), date(2027, 5, 31), date(2027, 8, 31));
  }

  @Test
  void endDateAfterCountsMonthEndOccurrences() {
    assertThat(Schedule.endDateAfter(date(2026, 9, 30), CadenceUnit.MONTH, 1, 2))
        .isEqualTo(date(2026, 10, 31));
  }

  @Test
  void monthlyFrom29thOfJanuaryIsNotMonthEnd() {
    Schedule monthly = open(date(2026, 1, 29), CadenceUnit.MONTH, 1);

    assertThat(monthly.nextOccurrences(date(2026, 1, 29), 2))
        .containsExactly(date(2026, 2, 28), date(2026, 3, 29));
  }

  @Test
  void yearlyFromMonthEndStartKeepsItsDay() {
    Schedule yearly = open(date(2026, 9, 30), CadenceUnit.YEAR, 1);

    assertThat(yearly.nextOccurrences(date(2026, 9, 30), 1)).containsExactly(date(2027, 9, 30));
    assertThat(open(date(2026, 2, 28), CadenceUnit.YEAR, 1).nextOccurrences(date(2026, 2, 28), 2))
        .containsExactly(date(2027, 2, 28), date(2028, 2, 28));
  }

  @Test
  void everyTwoMonthsOnThe31stClampsEachTime() {
    Schedule schedule = open(date(2026, 8, 31), CadenceUnit.MONTH, 2);

    assertThat(schedule.nextOccurrences(date(2026, 8, 30), 4))
        .containsExactly(
            date(2026, 8, 31), date(2026, 10, 31), date(2026, 12, 31), date(2027, 2, 28));
  }

  @Test
  void quarterlyIsEveryThreeMonths() {
    Schedule quarterly = open(date(2026, 1, 15), CadenceUnit.MONTH, 3);

    assertThat(quarterly.nextOccurrences(date(2026, 1, 15), 3))
        .containsExactly(date(2026, 4, 15), date(2026, 7, 15), date(2026, 10, 15));
  }

  // ── years ───────────────────────────────────────────────────────────────────

  @Test
  void yearlyOn29FebruaryFallsBackTo28FebruaryInNonLeapYears() {
    Schedule yearly = open(date(2024, 2, 29), CadenceUnit.YEAR, 1);

    assertThat(yearly.nextOccurrences(date(2024, 2, 29), 4))
        .containsExactly(
            date(2025, 2, 28), date(2026, 2, 28), date(2027, 2, 28), date(2028, 2, 29));
  }

  @Test
  void everyTwoYearsStepsTwoYears() {
    Schedule schedule = open(date(2026, 3, 1), CadenceUnit.YEAR, 2);

    assertThat(schedule.nextOccurrences(date(2026, 3, 1), 2))
        .containsExactly(date(2028, 3, 1), date(2030, 3, 1));
  }

  // ── the window (from, to] ───────────────────────────────────────────────────

  @Test
  void occurrencesBetweenExcludeTheFromDateAndIncludeTheToDate() {
    Schedule monthly = open(date(2026, 1, 15), CadenceUnit.MONTH, 1);

    assertThat(monthly.occurrencesBetween(date(2026, 2, 15), date(2026, 4, 15)))
        .containsExactly(date(2026, 3, 15), date(2026, 4, 15));
  }

  @Test
  void occurrencesBetweenNeverReachBeforeTheStart() {
    Schedule monthly = open(date(2026, 3, 10), CadenceUnit.MONTH, 1);

    assertThat(monthly.occurrencesBetween(date(2025, 12, 31), date(2026, 4, 30)))
        .containsExactly(date(2026, 3, 10), date(2026, 4, 10));
  }

  @Test
  void emptyWindowHasNoOccurrences() {
    Schedule daily = open(date(2026, 1, 1), CadenceUnit.DAY, 1);

    assertThat(daily.occurrencesBetween(date(2026, 5, 1), date(2026, 5, 1))).isEmpty();
    assertThat(daily.occurrencesBetween(date(2026, 5, 2), date(2026, 5, 1))).isEmpty();
  }

  @Test
  void windowStraddlingClampedMonthFindsTheClampedDate() {
    Schedule monthly = open(date(2026, 1, 31), CadenceUnit.MONTH, 1);

    assertThat(monthly.occurrencesBetween(date(2026, 2, 1), date(2026, 3, 1)))
        .containsExactly(date(2026, 2, 28));
  }

  // ── the end date ────────────────────────────────────────────────────────────

  @Test
  void endDateIsAnInclusiveBound() {
    Schedule schedule = new Schedule(date(2026, 1, 15), CadenceUnit.MONTH, 1, date(2026, 3, 15));

    assertThat(schedule.occurrencesBetween(date(2026, 1, 1), date(2026, 12, 31)))
        .containsExactly(date(2026, 1, 15), date(2026, 2, 15), date(2026, 3, 15));
  }

  @Test
  void endDateBetweenOccurrencesStopsAtTheLastOneBeforeIt() {
    Schedule schedule = new Schedule(date(2026, 1, 15), CadenceUnit.MONTH, 1, date(2026, 3, 14));

    assertThat(schedule.nextOccurrences(date(2026, 1, 1), 10))
        .containsExactly(date(2026, 1, 15), date(2026, 2, 15));
  }

  @Test
  void nextOccurrencesAfterTheEndAreEmpty() {
    Schedule schedule = new Schedule(date(2026, 1, 15), CadenceUnit.MONTH, 1, date(2026, 3, 15));

    assertThat(schedule.nextOccurrences(date(2026, 3, 15), 3)).isEmpty();
  }

  // ── "after K occurrences" → the K-th date ──────────────────────────────────

  @Test
  void countOfDaysEndsOnTheLastCountedOccurrence() {
    assertThat(Schedule.endDateAfter(date(2026, 1, 1), CadenceUnit.DAY, 3, 4))
        .isEqualTo(date(2026, 1, 10));
  }

  @Test
  void countOfWeeksEndsOnTheLastCountedOccurrence() {
    assertThat(Schedule.endDateAfter(date(2026, 9, 28), CadenceUnit.WEEK, 2, 3))
        .isEqualTo(date(2026, 10, 26));
  }

  @Test
  void countOfMonthsEndsOnTheLastCountedOccurrenceWithTheClamp() {
    assertThat(Schedule.endDateAfter(date(2026, 1, 31), CadenceUnit.MONTH, 1, 2))
        .isEqualTo(date(2026, 2, 28));
    assertThat(Schedule.endDateAfter(date(2026, 1, 31), CadenceUnit.MONTH, 1, 12))
        .isEqualTo(date(2026, 12, 31));
  }

  @Test
  void countOfYearsEndsOnTheLastCountedOccurrence() {
    assertThat(Schedule.endDateAfter(date(2024, 2, 29), CadenceUnit.YEAR, 1, 2))
        .isEqualTo(date(2025, 2, 28));
  }

  @Test
  void afterOneOccurrenceEndsOnTheStart() {
    assertThat(Schedule.endDateAfter(date(2026, 5, 5), CadenceUnit.MONTH, 1, 1))
        .isEqualTo(date(2026, 5, 5));
  }

  @Test
  void countedScheduleHasExactlyThatManyOccurrences() {
    LocalDate start = date(2026, 1, 31);
    Schedule schedule =
        new Schedule(
            start, CadenceUnit.MONTH, 1, Schedule.endDateAfter(start, CadenceUnit.MONTH, 1, 6));

    assertThat(schedule.nextOccurrences(start.minusDays(1), 100)).hasSize(6);
  }

  // ── validation ──────────────────────────────────────────────────────────────

  @Test
  void rejectsCadenceBelowOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> open(date(2026, 1, 1), CadenceUnit.MONTH, 0));
  }

  @Test
  void rejectsEndDateBeforeStart() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Schedule(date(2026, 3, 1), CadenceUnit.MONTH, 1, date(2026, 2, 28)));
  }

  @Test
  void rejectsAfterZeroOccurrences() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Schedule.endDateAfter(date(2026, 1, 1), CadenceUnit.DAY, 1, 0));
  }

  // ── the stored cadence code ─────────────────────────────────────────────────

  @Test
  void cadenceUnitRoundTripsItsStoredCode() {
    for (CadenceUnit unit : CadenceUnit.values()) {
      assertThat(CadenceUnit.fromCode(unit.code())).isEqualTo(unit);
    }
    assertThat(CadenceUnit.MONTH.code()).isEqualTo("month");
  }

  @Test
  void cadenceUnitRejectsUnknownCode() {
    assertThatIllegalArgumentException().isThrownBy(() -> CadenceUnit.fromCode("fortnight"));
  }

  // ── the cost normalisation (data-model §14.4) ───────────────────────────────

  private static BigDecimal cents(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  @Test
  void monthsAndYearsNormaliseExactly() {
    BigDecimal amount = new BigDecimal("30");

    assertThat(open(date(2026, 1, 31), CadenceUnit.MONTH, 1).perMonth(amount))
        .isEqualByComparingTo("30");
    assertThat(open(date(2026, 1, 31), CadenceUnit.MONTH, 1).perYear(amount))
        .isEqualByComparingTo("360");
    assertThat(open(date(2026, 1, 31), CadenceUnit.MONTH, 3).perMonth(amount))
        .isEqualByComparingTo("10");
    assertThat(open(date(2026, 1, 31), CadenceUnit.MONTH, 3).perYear(amount))
        .isEqualByComparingTo("120");
    assertThat(open(date(2026, 1, 31), CadenceUnit.YEAR, 1).perYear(amount))
        .isEqualByComparingTo("30");
    assertThat(open(date(2026, 1, 31), CadenceUnit.YEAR, 2).perMonth(amount))
        .isEqualByComparingTo("1.25");
  }

  @Test
  void daysAndWeeksNormaliseThroughYearOfDays() {
    // A year is 365 days for this.
    BigDecimal amount = BigDecimal.TEN;

    assertThat(open(date(2026, 1, 1), CadenceUnit.DAY, 1).perYear(amount))
        .isEqualByComparingTo("3650");
    assertThat(cents(open(date(2026, 1, 1), CadenceUnit.DAY, 1).perMonth(amount)))
        .isEqualByComparingTo("304.17");
    assertThat(cents(open(date(2026, 1, 1), CadenceUnit.DAY, 10).perYear(amount)))
        .isEqualByComparingTo("365.00");
    assertThat(cents(open(date(2026, 1, 1), CadenceUnit.WEEK, 1).perYear(amount)))
        .isEqualByComparingTo("521.43");
    assertThat(cents(open(date(2026, 1, 1), CadenceUnit.WEEK, 2).perMonth(amount)))
        .isEqualByComparingTo("21.73");
  }

  @Test
  void signOfTheAmountCarriesThrough() {
    assertThat(open(date(2026, 1, 31), CadenceUnit.MONTH, 1).perYear(new BigDecimal("-9.99")))
        .isEqualByComparingTo("-119.88");
  }
}
