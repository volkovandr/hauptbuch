package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tier (CLAUDE.md §6): month bucketing and partial-bucket labelling (reporting.md §8.2). */
class MonthBucketTest {

  @Test
  void oneFullMonthIsNotPartial() {
    List<MonthBucket> buckets =
        MonthBucket.monthsBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).partial()).isFalse();
    assertThat(buckets.get(0).label()).isEqualTo("Jan 2026");
    assertThat(buckets.get(0).key()).isEqualTo("2026-01");
  }

  @Test
  void rangeCuttingTheLastMonthShortIsLabelledPartialAtTheEnd() {
    List<MonthBucket> buckets =
        MonthBucket.monthsBetween(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 12));

    assertThat(buckets).extracting(MonthBucket::key).containsExactly("2026-08", "2026-09");
    assertThat(buckets.get(0).partial()).isFalse();
    assertThat(buckets.get(1).partial()).isTrue();
    assertThat(buckets.get(1).label()).isEqualTo("Sep 2026 (to 12th)");
  }

  @Test
  void rangeStartingMidMonthIsLabelledPartialAtTheStart() {
    List<MonthBucket> buckets =
        MonthBucket.monthsBetween(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 28));

    assertThat(buckets.get(0).partial()).isTrue();
    assertThat(buckets.get(0).label()).isEqualTo("Jan 2026 (from 15th)");
    assertThat(buckets.get(1).partial()).isFalse();
  }

  @Test
  void everyCalendarMonthOverlappingTheRangeIsProduced() {
    List<MonthBucket> buckets =
        MonthBucket.monthsBetween(LocalDate.of(2026, 11, 20), LocalDate.of(2027, 2, 5));

    assertThat(buckets)
        .extracting(b -> b.month().toString())
        .containsExactly("2026-11", "2026-12", "2027-01", "2027-02");
  }

  @Test
  void singleDayRangeYieldsOnePartialBucket() {
    List<MonthBucket> buckets =
        MonthBucket.monthsBetween(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 15));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).month()).isEqualTo(YearMonth.of(2026, 3));
    assertThat(buckets.get(0).partial()).isTrue();
  }

  @Test
  void rangeClippedAtBothEndsOfOneMonthMentionsBothDays() {
    List<MonthBucket> buckets =
        MonthBucket.monthsBetween(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 20));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).label()).isEqualTo("Jan 2026 (10th–20th)");
  }
}
