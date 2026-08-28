package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tier: the two pure text helpers behind the landing-page tracking-stats line (CONTEXT.md
 * "Tracking stats"). {@link TrackingStatsText#duration} turns a span into words; {@link
 * TrackingStatsText#count} applies the German thousands separator and pluralises; {@link
 * TrackingStatsText#humaniseBytes} renders a byte total in decimal (SI) units to one decimal place.
 */
class TrackingStatsTextTest {

  @Test
  void durationRendersYearsAndMonths() {
    assertThat(TrackingStatsText.duration(LocalDate.of(2023, 12, 28), LocalDate.of(2026, 8, 28)))
        .isEqualTo("2 years and 8 months");
  }

  @Test
  void durationUsesSingularForOneYearOneMonth() {
    assertThat(TrackingStatsText.duration(LocalDate.of(2025, 7, 28), LocalDate.of(2026, 8, 28)))
        .isEqualTo("1 year and 1 month");
  }

  @Test
  void durationOmitsMonthsWhenExactlyWholeYears() {
    assertThat(TrackingStatsText.duration(LocalDate.of(2023, 8, 28), LocalDate.of(2026, 8, 28)))
        .isEqualTo("3 years");
  }

  @Test
  void durationOmitsYearsWhenUnderOneYear() {
    assertThat(TrackingStatsText.duration(LocalDate.of(2026, 1, 28), LocalDate.of(2026, 8, 28)))
        .isEqualTo("7 months");
  }

  @Test
  void durationForSpanShorterThanOneMonth() {
    assertThat(TrackingStatsText.duration(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 28)))
        .isEqualTo("less than a month");
  }

  @Test
  void durationForSameDay() {
    assertThat(TrackingStatsText.duration(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 28)))
        .isEqualTo("less than a month");
  }

  @Test
  void durationForStartAfterToday() {
    assertThat(TrackingStatsText.duration(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 8, 28)))
        .isEqualTo("less than a month");
  }

  @Test
  void countGroupsThousandsAndPluralises() {
    assertThat(TrackingStatsText.count(1234, "transaction")).isEqualTo("1.234 transactions");
  }

  @Test
  void countUsesSingularForOne() {
    assertThat(TrackingStatsText.count(1, "receipt")).isEqualTo("1 receipt");
  }

  @Test
  void countHandlesZero() {
    assertThat(TrackingStatsText.count(0, "transaction")).isEqualTo("0 transactions");
  }

  @Test
  void humaniseBytesRendersBareBytesToOneDecimal() {
    assertThat(TrackingStatsText.humaniseBytes(512)).isEqualTo("512,0 B");
  }

  @Test
  void humaniseBytesStepsUpToKilobytes() {
    assertThat(TrackingStatsText.humaniseBytes(1_500)).isEqualTo("1,5 kB");
  }

  @Test
  void humaniseBytesStepsUpToMegabytes() {
    assertThat(TrackingStatsText.humaniseBytes(847_000_000)).isEqualTo("847,0 MB");
  }

  @Test
  void humaniseBytesStepsUpToGigabytes() {
    assertThat(TrackingStatsText.humaniseBytes(2_500_000_000L)).isEqualTo("2,5 GB");
  }

  @Test
  void humaniseBytesStaysInGigabytesForVeryLargeTotals() {
    assertThat(TrackingStatsText.humaniseBytes(1_234_500_000_000L)).isEqualTo("1.234,5 GB");
  }

  @Test
  void humaniseBytesPromotesAtTheRoundingBoundaryRatherThanShowing1000() {
    // 999.99 kB rounds to 1000,0 at one decimal — it must read "1,0 MB", not "1.000,0 kB".
    assertThat(TrackingStatsText.humaniseBytes(999_990)).isEqualTo("1,0 MB");
    assertThat(TrackingStatsText.humaniseBytes(999_990_000)).isEqualTo("1,0 GB");
  }

  @Test
  void humaniseBytesRendersZero() {
    assertThat(TrackingStatsText.humaniseBytes(0)).isEqualTo("0,0 B");
  }
}
