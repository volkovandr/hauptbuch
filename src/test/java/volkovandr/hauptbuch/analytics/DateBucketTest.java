package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): bucketing and partial-bucket labelling for every {@link
 * DateGranularity} rung of the ladder (reporting.md §8.2, stage e4).
 */
class DateBucketTest {

  // ── month (pre-e4 behaviour, unchanged) ─────────────────────────────────────

  @Test
  void oneFullMonthIsNotPartial() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.MONTH, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).partial()).isFalse();
    assertThat(buckets.get(0).label()).isEqualTo("Jan 2026");
    assertThat(buckets.get(0).key()).isEqualTo("2026-01");
  }

  @Test
  void rangeCuttingTheLastMonthShortIsLabelledPartialAtTheEnd() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.MONTH, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 12));

    assertThat(buckets).extracting(DateBucket::key).containsExactly("2026-08", "2026-09");
    assertThat(buckets.get(0).partial()).isFalse();
    assertThat(buckets.get(1).partial()).isTrue();
    assertThat(buckets.get(1).label()).isEqualTo("Sep 2026 (to 12th)");
  }

  @Test
  void rangeStartingMidMonthIsLabelledPartialAtTheStart() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.MONTH, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 28));

    assertThat(buckets.get(0).partial()).isTrue();
    assertThat(buckets.get(0).label()).isEqualTo("Jan 2026 (from 15th)");
    assertThat(buckets.get(1).partial()).isFalse();
  }

  @Test
  void everyCalendarMonthOverlappingTheRangeIsProduced() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.MONTH, LocalDate.of(2026, 11, 20), LocalDate.of(2027, 2, 5));

    assertThat(buckets)
        .extracting(DateBucket::key)
        .containsExactly("2026-11", "2026-12", "2027-01", "2027-02");
  }

  @Test
  void rangeClippedAtBothEndsOfOneMonthMentionsBothDays() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.MONTH, LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 20));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).label()).isEqualTo("Jan 2026 (10th–20th)");
  }

  // ── week (Monday start, ISO) ─────────────────────────────────────────────────

  @Test
  void oneFullWeekIsNotPartial() {
    // Monday 2026-09-14 through Sunday 2026-09-20.
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.WEEK, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).partial()).isFalse();
    assertThat(buckets.get(0).label()).isEqualTo("w/c 14 Sep 2026");
    assertThat(buckets.get(0).key()).isEqualTo("2026-09-14");
  }

  @Test
  void weekBucketStartsAtTheMondayOnOrBeforeRangeStartingMidWeek() {
    // Wednesday 2026-09-16 falls inside the week starting Monday 2026-09-14.
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.WEEK, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 20));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).key()).isEqualTo("2026-09-14");
    assertThat(buckets.get(0).partial()).isTrue();
    assertThat(buckets.get(0).label()).isEqualTo("w/c 14 Sep 2026 (from 16 Sep)");
  }

  @Test
  void weekCrossingMonthBoundaryLabelsTheClippedEdgeWithItsOwnMonth() {
    // The week starting Monday 2026-08-31 runs into September; clip the range to end on the 2nd.
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.WEEK, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 2));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).partial()).isTrue();
    assertThat(buckets.get(0).label()).isEqualTo("w/c 31 Aug 2026 (to 2 Sep)");
  }

  @Test
  void everyIsoWeekOverlappingTheRangeIsProduced() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.WEEK, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 28));

    assertThat(buckets)
        .extracting(DateBucket::key)
        .containsExactly("2026-09-14", "2026-09-21", "2026-09-28");
  }

  // ── day (never partial) ──────────────────────────────────────────────────────

  @Test
  void oneDayBucketPerCalendarDayNeverPartial() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.DAY, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 16));

    assertThat(buckets)
        .extracting(DateBucket::key)
        .containsExactly("2026-09-14", "2026-09-15", "2026-09-16");
    assertThat(buckets).allSatisfy(b -> assertThat(b.partial()).isFalse());
    assertThat(buckets.get(0).label()).isEqualTo("14 Sep 2026");
  }

  // ── year ──────────────────────────────────────────────────────────────────────

  @Test
  void oneFullYearIsNotPartial() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.YEAR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).partial()).isFalse();
    assertThat(buckets.get(0).label()).isEqualTo("2026");
    assertThat(buckets.get(0).key()).isEqualTo("2026");
  }

  @Test
  void yearClippedAtBothEndsMentionsBothClippedDaysWithTheirOwnMonth() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.YEAR, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 30));

    assertThat(buckets).hasSize(1);
    assertThat(buckets.get(0).partial()).isTrue();
    assertThat(buckets.get(0).label()).isEqualTo("2026 (1 Mar–30 Sep)");
  }

  @Test
  void everyCalendarYearOverlappingTheRangeIsProduced() {
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.YEAR, LocalDate.of(2025, 11, 20), LocalDate.of(2027, 2, 5));

    assertThat(buckets).extracting(DateBucket::key).containsExactly("2025", "2026", "2027");
  }
}
