package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The register's date-range quick presets (issue transaction-register-ui/12). */
class DatePresetTest {

  @Test
  void last90DaysCountsTodayAsOneOfTheNinety() {
    DatePreset.DateRange range = DatePreset.LAST_90_DAYS.rangeAt(LocalDate.of(2026, 10, 21));

    assertThat(range.from()).isEqualTo(LocalDate.of(2026, 7, 24));
    assertThat(range.to()).isNull();
  }

  @Test
  void last365DaysLeavesTheUpperBoundOpen() {
    DatePreset.DateRange range = DatePreset.LAST_365_DAYS.rangeAt(LocalDate.of(2026, 10, 21));

    assertThat(range.from()).isEqualTo(LocalDate.of(2025, 10, 22));
    assertThat(range.to()).isNull();
  }

  @Test
  void previousMonthIsTheWholeCalendarMonthBeforeToday() {
    DatePreset.DateRange range = DatePreset.PREVIOUS_MONTH.rangeAt(LocalDate.of(2026, 10, 21));

    assertThat(range.from()).isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(range.to()).isEqualTo(LocalDate.of(2026, 9, 30));
  }

  @Test
  void previousMonthInJanuaryIsDecemberOfThePreviousYear() {
    DatePreset.DateRange range = DatePreset.PREVIOUS_MONTH.rangeAt(LocalDate.of(2026, 1, 15));

    assertThat(range.from()).isEqualTo(LocalDate.of(2025, 12, 1));
    assertThat(range.to()).isEqualTo(LocalDate.of(2025, 12, 31));
  }

  @Test
  void previousMonthHonoursFebruaryLengths() {
    assertThat(DatePreset.PREVIOUS_MONTH.rangeAt(LocalDate.of(2026, 3, 31)).to())
        .isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(DatePreset.PREVIOUS_MONTH.rangeAt(LocalDate.of(2024, 3, 1)).to())
        .isEqualTo(LocalDate.of(2024, 2, 29));
  }

  @Test
  void parsesTheSubmittedTokenAndIgnoresUnknownOnes() {
    assertThat(DatePreset.fromParam("last-90-days")).contains(DatePreset.LAST_90_DAYS);
    assertThat(DatePreset.fromParam("previous-month")).contains(DatePreset.PREVIOUS_MONTH);
    assertThat(DatePreset.fromParam("bogus")).isEmpty();
    assertThat(DatePreset.fromParam(null)).isEmpty();
    assertThat(DatePreset.fromParam(" ")).isEmpty();
  }
}
