package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): the start/end anchor grammar (reporting.md §8.1) — pure date
 * arithmetic, resolved against a fixed "today" so every named shortcut in the doc's table is
 * checked, plus the week/quarter units the shortcuts don't exercise.
 */
class RangeResolverTest {

  // A Saturday, so week-start (Monday) arithmetic is actually exercised.
  private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);

  private static LocalDate resolve(RangeEndpoint endpoint) {
    return RangeResolver.resolve(new DateRange(endpoint, endpoint), TODAY).start();
  }

  @Test
  void literalEndpointIgnoresToday() {
    RangeEndpoint literal = new RangeEndpoint.Literal(LocalDate.of(2020, 1, 1));
    assertThat(resolve(literal)).isEqualTo(LocalDate.of(2020, 1, 1));
  }

  @Test
  void yearToDate() {
    RangeResolver.ResolvedRange range = RangeResolver.resolve(DateRange.yearToDate(), TODAY);
    assertThat(range.start()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(range.end()).isEqualTo(TODAY);
  }

  @Test
  void last12MonthsIncludingCurrent() {
    RangeResolver.ResolvedRange range = RangeResolver.resolve(DateRange.last12Months(), TODAY);
    assertThat(range.start()).isEqualTo(LocalDate.of(2025, 10, 1));
    assertThat(range.end()).isEqualTo(TODAY);
  }

  @Test
  void previousMonth() {
    RangeResolver.ResolvedRange range = RangeResolver.resolve(DateRange.previousMonth(), TODAY);
    assertThat(range.start()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(range.end()).isEqualTo(LocalDate.of(2026, 8, 31));
  }

  @Test
  void previousYear() {
    RangeResolver.ResolvedRange range = RangeResolver.resolve(DateRange.previousYear(), TODAY);
    assertThat(range.start()).isEqualTo(LocalDate.of(2025, 1, 1));
    assertThat(range.end()).isEqualTo(LocalDate.of(2025, 12, 31));
  }

  @Test
  void currentMonthOnly() {
    RangeResolver.ResolvedRange range = RangeResolver.resolve(DateRange.currentMonth(), TODAY);
    assertThat(range.start()).isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(range.end()).isEqualTo(TODAY);
  }

  @Test
  void weekStartsMonday() {
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.WEEK, 0, RangeEdge.START)))
        .isEqualTo(LocalDate.of(2026, 9, 7));
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.WEEK, 0, RangeEdge.END)))
        .isEqualTo(LocalDate.of(2026, 9, 13));
  }

  @Test
  void previousWeek() {
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.WEEK, -1, RangeEdge.START)))
        .isEqualTo(LocalDate.of(2026, 8, 31));
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.WEEK, -1, RangeEdge.END)))
        .isEqualTo(LocalDate.of(2026, 9, 6));
  }

  @Test
  void currentQuarter() {
    // September falls in Q3 (Jul-Sep).
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.QUARTER, 0, RangeEdge.START)))
        .isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.QUARTER, 0, RangeEdge.END)))
        .isEqualTo(LocalDate.of(2026, 9, 30));
  }

  @Test
  void quarterOffsetCrossesYearBoundary() {
    // Two quarters back from Q3 2026 is Q1 2026; three back is Q4 2025.
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.QUARTER, -2, RangeEdge.START)))
        .isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.QUARTER, -3, RangeEdge.START)))
        .isEqualTo(LocalDate.of(2025, 10, 1));
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.QUARTER, -3, RangeEdge.END)))
        .isEqualTo(LocalDate.of(2025, 12, 31));
  }

  @Test
  void anEndEndpointMayResolveIntoTheFuture() {
    // month, 0, end on the 12th resolves past today (reporting.md §8.1) — the engine does not clamp
    // it; only the closing-balance asOf date is clamped (§8.2), a separate rule.
    assertThat(resolve(new RangeEndpoint.Relative(RangeUnit.MONTH, 0, RangeEdge.END)))
        .isEqualTo(LocalDate.of(2026, 9, 30));
  }
}
