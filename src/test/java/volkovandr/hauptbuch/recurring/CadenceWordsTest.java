package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): a template's cadence in words, as the recurring page lists it
 * (recurring sub-plan slice b) — "every 2 months on the 31st". The anchor comes from the start
 * date.
 */
class CadenceWordsTest {

  private static String words(int year, int month, int day, CadenceUnit unit, int every) {
    return CadenceWords.of(new Schedule(LocalDate.of(year, month, day), unit, every, null));
  }

  @Test
  void everyDay() {
    assertThat(words(2026, 1, 1, CadenceUnit.DAY, 1)).isEqualTo("every day");
  }

  @Test
  void everyFewDays() {
    assertThat(words(2026, 1, 1, CadenceUnit.DAY, 10)).isEqualTo("every 10 days");
  }

  @Test
  void everyWeekNamesTheWeekday() {
    // 28 September 2026 is a Monday
    assertThat(words(2026, 9, 28, CadenceUnit.WEEK, 1)).isEqualTo("every week on Monday");
  }

  @Test
  void everyFewWeeksNamesTheWeekday() {
    assertThat(words(2026, 10, 2, CadenceUnit.WEEK, 2)).isEqualTo("every 2 weeks on Friday");
  }

  @Test
  void everyMonthNamesTheDayOfMonth() {
    assertThat(words(2026, 1, 31, CadenceUnit.MONTH, 1)).isEqualTo("every month on the 31st");
  }

  @Test
  void everyFewMonthsNamesTheDayOfMonth() {
    assertThat(words(2026, 1, 31, CadenceUnit.MONTH, 2)).isEqualTo("every 2 months on the 31st");
  }

  @Test
  void dayOfMonthOrdinals() {
    assertThat(words(2026, 1, 1, CadenceUnit.MONTH, 1)).endsWith("the 1st");
    assertThat(words(2026, 1, 2, CadenceUnit.MONTH, 1)).endsWith("the 2nd");
    assertThat(words(2026, 1, 3, CadenceUnit.MONTH, 1)).endsWith("the 3rd");
    assertThat(words(2026, 1, 4, CadenceUnit.MONTH, 1)).endsWith("the 4th");
    assertThat(words(2026, 1, 11, CadenceUnit.MONTH, 1)).endsWith("the 11th");
    assertThat(words(2026, 1, 12, CadenceUnit.MONTH, 1)).endsWith("the 12th");
    assertThat(words(2026, 1, 13, CadenceUnit.MONTH, 1)).endsWith("the 13th");
    assertThat(words(2026, 1, 21, CadenceUnit.MONTH, 1)).endsWith("the 21st");
    assertThat(words(2026, 1, 22, CadenceUnit.MONTH, 1)).endsWith("the 22nd");
    assertThat(words(2026, 1, 23, CadenceUnit.MONTH, 1)).endsWith("the 23rd");
    assertThat(words(2026, 1, 30, CadenceUnit.MONTH, 1)).endsWith("the 30th");
  }

  @Test
  void everyYearNamesTheDayAndMonth() {
    assertThat(words(2024, 2, 29, CadenceUnit.YEAR, 1)).isEqualTo("every year on 29 February");
  }

  @Test
  void everyFewYearsNamesTheDayAndMonth() {
    assertThat(words(2026, 3, 1, CadenceUnit.YEAR, 2)).isEqualTo("every 2 years on 1 March");
  }
}
