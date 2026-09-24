package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;

/**
 * Unit tier (CLAUDE.md §6): {@link GridData#withDays} — merging one expanded Date row's own days
 * (reporting.md §9.1, stage e4b) under the composite keys the grid's day rows look them up by — and
 * {@link GridData#hasDataFor}, which tells a touched node from an untouched one (reporting issue
 * 08).
 */
class GridDataTest {

  private static RawTurnoverCell turnover(String bucketKey) {
    return new RawTurnoverCell(
        "1", "Food", "expense", bucketKey, "EUR", BigDecimal.TEN, BigDecimal.TEN, 0, 1, 1);
  }

  @Test
  void daysTurnoverJoinsTheBucketsOwnUnderCompositeBucketKeys() {
    GridData month =
        new GridData(Map.of(Leg.NET, List.of(turnover("2026-01"))), Map.of(), Map.of());
    GridData days =
        new GridData(Map.of(Leg.NET, List.of(turnover("2026-01-15"))), Map.of(), Map.of());

    GridData merged = month.withDays("2026-01", days);

    assertThat(merged.turnoverByLeg().get(Leg.NET))
        .extracting(RawTurnoverCell::bucketKey)
        .containsExactly("2026-01", "2026-01|2026-01-15");
    assertThat(month.turnoverByLeg().get(Leg.NET)).hasSize(1);
  }

  @Test
  void daysClosingBalanceAndAsOfAreKeyedUnderCompositeBucketKeys() {
    RawBalanceCell cash = new RawBalanceCell("1", "Cash", "asset", "EUR", BigDecimal.TEN);
    GridData month =
        new GridData(
            Map.of(),
            Map.of("2026-09", List.of(cash)),
            Map.of("2026-09", LocalDate.of(2026, 9, 12)));
    GridData days =
        new GridData(
            Map.of(),
            Map.of("2026-09-03", List.of(cash)),
            Map.of("2026-09-03", LocalDate.of(2026, 9, 3)));

    GridData merged = month.withDays("2026-09", days);

    assertThat(merged.balanceByBucketKey()).containsOnlyKeys("2026-09", "2026-09|2026-09-03");
    assertThat(merged.asOfByBucketKey())
        .containsEntry("2026-09|2026-09-03", LocalDate.of(2026, 9, 3))
        .containsEntry("2026-09", LocalDate.of(2026, 9, 12));
  }

  @Test
  void hasDataForAnyTurnoverOrBalanceRowOfThatNodeOnly() {
    RawBalanceCell cash = new RawBalanceCell("2", "Cash", "asset", "EUR", BigDecimal.TEN);
    GridData data =
        new GridData(
            Map.of(Leg.NET, List.of(turnover("2026-01"))),
            Map.of("total", List.of(cash)),
            Map.of("total", LocalDate.of(2026, 1, 31)));

    assertThat(data.hasDataFor("1")).isTrue();
    assertThat(data.hasDataFor("2")).isTrue();
    assertThat(data.hasDataFor("3")).isFalse();
  }

  @Test
  void hasNestedDataForMatchesTheInnerKeyUnderAnyOuterNode() {
    RawTurnoverCell nested =
        new RawTurnoverCell(
            "1|7", "Bakery", "expense", "2026-01", "EUR", BigDecimal.TEN, BigDecimal.TEN, 0, 1, 1);
    GridData data = new GridData(Map.of(Leg.NET, List.of(nested)), Map.of(), Map.of());

    assertThat(data.hasNestedDataFor("7")).isTrue();
    assertThat(data.hasNestedDataFor("1")).isFalse();
  }
}
