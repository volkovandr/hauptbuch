package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.repository.QueryConstraints;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.ReportQueryRepository;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportDataFetcher} — which {@link ReportQueryRepository} method
 * a dimension and measure combination maps to, and the closing-balance as-of-date resolution (the
 * range clip and the today clamp, reporting.md §8.2) — with the repository mocked. The SQL itself
 * is {@code ReportQuerySqlLogicTest}'s job.
 */
class ReportDataFetcherTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);

  private final ReportQueryRepository queryRepository = mock();
  private final ReportDataFetcher fetcher = new ReportDataFetcher(queryRepository);

  private static ReportSpec turnoverSpec(Dimension nonDateDim) {
    List<Dimension> rows = nonDateDim == null ? List.of() : List.of(nonDateDim);
    return new ReportSpec(
        rows,
        List.of(Dimension.DATE),
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        false,
        false,
        true);
  }

  private static AxisPlan axesFor(Dimension nonDateDim) {
    return new AxisPlan(nonDateDim, Dimension.DATE, nonDateDim, false, true);
  }

  private static RangeResolver.ResolvedRange resolved(LocalDate start, LocalDate end) {
    return new RangeResolver.ResolvedRange(start, end);
  }

  // ── candidatesFor ─────────────────────────────────────────────────────────

  @Test
  void categoryAndAccountDimensionsFetchTopLevelAccounts() {
    when(queryRepository.topLevelAccounts(List.of("expense"), true))
        .thenReturn(List.of(new TopLevelNode("1", "Food", "expense")));

    Map<String, TopLevelNode> candidates =
        fetcher.candidatesFor(Dimension.CATEGORY, List.of("expense"), Scope.ofTypes("expense"));

    assertThat(candidates).containsOnlyKeys("1");
  }

  @Test
  void tagDimensionFetchesTopLevelTags() {
    when(queryRepository.topLevelTags()).thenReturn(List.of(new TopLevelNode("1", "Car", null)));

    assertThat(fetcher.candidatesFor(Dimension.TAG, List.of("expense"), Scope.ofTypes("expense")))
        .containsOnlyKeys("1");
  }

  @Test
  void noDimensionFetchesNoCandidates() {
    assertThat(fetcher.candidatesFor(null, List.of("expense"), Scope.ofTypes("expense"))).isEmpty();
  }

  // ── fetchGridData: turnover ───────────────────────────────────────────────

  @Test
  void categoryDimensionFetchesAccountTreeTurnover() {
    fetcher.fetchGridData(
        turnoverSpec(Dimension.CATEGORY),
        axesFor(Dimension.CATEGORY),
        List.of("expense"),
        resolved(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
        List.of(),
        TODAY,
        "EUR",
        Set.of());

    verify(queryRepository)
        .accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            "EUR",
            "NET",
            true,
            false,
            DateGranularity.MONTH,
            QueryConstraints.NONE);
  }

  @Test
  void tagDimensionFetchesTagTurnover() {
    fetcher.fetchGridData(
        turnoverSpec(Dimension.TAG),
        axesFor(Dimension.TAG),
        List.of("expense"),
        resolved(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
        List.of(),
        TODAY,
        "EUR",
        Set.of());

    verify(queryRepository)
        .tagTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            "EUR",
            "NET",
            true,
            false,
            DateGranularity.MONTH,
            QueryConstraints.NONE);
  }

  @Test
  void noDimensionFetchesTotalTurnover() {
    fetcher.fetchGridData(
        turnoverSpec(null),
        axesFor(null),
        List.of("expense"),
        resolved(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
        List.of(),
        TODAY,
        "EUR",
        Set.of());

    verify(queryRepository)
        .totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            "EUR",
            "NET",
            true,
            false,
            DateGranularity.MONTH,
            QueryConstraints.NONE);
  }

  // ── fetchGridData: closing balance ────────────────────────────────────────

  private static ReportSpec closingBalanceSpec(
      boolean dateOnColumns, boolean includePendingReview) {
    return new ReportSpec(
        List.of(Dimension.ACCOUNT),
        dateOnColumns ? List.of(Dimension.DATE) : List.of(),
        List.of(),
        List.of(Measure.closingBalance(PresentationCurrency.BASE)),
        new Scope(java.util.Set.of("asset"), true, includePendingReview),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        false,
        false,
        true);
  }

  @Test
  void closingBalanceWithNoDateAxisFetchesOneAsOf() {
    AxisPlan axes = new AxisPlan(Dimension.ACCOUNT, null, Dimension.ACCOUNT, false, false);

    GridData data =
        fetcher.fetchGridData(
            closingBalanceSpec(false, false),
            axes,
            List.of("asset"),
            resolved(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31)),
            List.of(),
            TODAY,
            "EUR",
            Set.of());

    verify(queryRepository)
        .accountTreeClosingBalance(
            List.of("asset"), LocalDate.of(2026, 1, 31), true, false, QueryConstraints.NONE);
    assertThat(data.balanceByBucketKey()).containsOnlyKeys("total");
  }

  @Test
  void closingBalanceForwardsIncludePendingReviewFromScope() {
    AxisPlan axes = new AxisPlan(Dimension.ACCOUNT, null, Dimension.ACCOUNT, false, false);

    fetcher.fetchGridData(
        closingBalanceSpec(false, true),
        axes,
        List.of("asset"),
        resolved(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31)),
        List.of(),
        TODAY,
        "EUR",
        Set.of());

    verify(queryRepository)
        .accountTreeClosingBalance(
            List.of("asset"), LocalDate.of(2026, 1, 31), true, true, QueryConstraints.NONE);
  }

  @Test
  void closingBalanceAsOfUsesTheBucketsClippedEffectiveEndNotTheFullCalendarMonth() {
    when(queryRepository.accountTreeClosingBalance(
            anyList(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(List.of(new RawBalanceCell("1", "Cash", "asset", "EUR", BigDecimal.TEN)));
    // The report's range ends mid-January (the 20th); the bucket's calendar month runs through the
    // 31st, but the as-of date must respect the report's own clipped range end (reporting.md §8.2),
    // not the full month.
    List<DateBucket> buckets =
        DateBucket.bucketsBetween(
            DateGranularity.MONTH, LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 20));
    AxisPlan axes = new AxisPlan(Dimension.ACCOUNT, Dimension.DATE, Dimension.ACCOUNT, false, true);

    fetcher.fetchGridData(
        closingBalanceSpec(true, false),
        axes,
        List.of("asset"),
        resolved(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 20)),
        buckets,
        TODAY,
        "EUR",
        Set.of());

    verify(queryRepository)
        .accountTreeClosingBalance(
            List.of("asset"), LocalDate.of(2026, 1, 20), true, false, QueryConstraints.NONE);
  }

  @Test
  void closingBalanceAsOfDateIsClampedToTodayWhenTheBucketExtendsIntoTheFuture() {
    when(queryRepository.accountTreeClosingBalance(
            anyList(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(List.of(new RawBalanceCell("1", "Cash", "asset", "EUR", BigDecimal.TEN)));
    List<DateBucket> septemberBucket =
        DateBucket.bucketsBetween(
            DateGranularity.MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    AxisPlan axes = new AxisPlan(Dimension.ACCOUNT, Dimension.DATE, Dimension.ACCOUNT, false, true);

    fetcher.fetchGridData(
        closingBalanceSpec(true, false),
        axes,
        List.of("asset"),
        resolved(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
        septemberBucket,
        TODAY,
        "EUR",
        Set.of());

    // The bucket runs through 30 Sep, but TODAY is only the 12th — the as-of date must not run
    // ahead of today (reporting.md §8.2).
    verify(queryRepository)
        .accountTreeClosingBalance(List.of("asset"), TODAY, true, false, QueryConstraints.NONE);
  }

  // ── fetchGridData at an explicit granularity (a Date row's days, §9.1, stage e4b) ─────────

  @Test
  void explicitDayGranularityOverridesTheLaddersOwnRung() {
    fetcher.fetchGridData(
        turnoverSpec(Dimension.CATEGORY),
        new AxisPlan(Dimension.DATE, Dimension.CATEGORY, Dimension.CATEGORY, true, false),
        List.of("expense"),
        resolved(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 31)),
        List.of(),
        TODAY,
        "EUR",
        Set.of(),
        DateGranularity.DAY);

    verify(queryRepository)
        .accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 10),
            LocalDate.of(2026, 1, 31),
            "EUR",
            "NET",
            true,
            false,
            DateGranularity.DAY,
            QueryConstraints.NONE);
  }

  @Test
  void dayBucketsClosingBalanceIsOnePerDayClampedToToday() {
    // An expanded September's days (§9.1): each day's balance is as of that day, and a day past
    // TODAY (the 12th) is the balance as of today, not a balance that has not happened (§8.2).
    when(queryRepository.accountTreeClosingBalance(
            anyList(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(List.of(new RawBalanceCell("1", "Cash", "asset", "EUR", BigDecimal.TEN)));
    DateBucket september =
        DateBucket.bucketsBetween(
                DateGranularity.MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
            .get(0);

    GridData days =
        fetcher.fetchGridData(
            closingBalanceSpec(true, false),
            new AxisPlan(Dimension.DATE, Dimension.ACCOUNT, Dimension.ACCOUNT, true, false),
            List.of("asset"),
            september.effectiveRange(),
            september.days(),
            TODAY,
            "EUR",
            Set.of(),
            DateGranularity.DAY);

    assertThat(days.balanceByBucketKey()).hasSize(30);
    assertThat(days.asOfByBucketKey())
        .containsEntry("2026-09-03", LocalDate.of(2026, 9, 3))
        .containsEntry("2026-09-30", TODAY);
  }

  // ── childCandidatesFor / realId (stage e's parent-key resolution) ──────────

  @Test
  void childCandidatesForUsesTheWholeKeyAsTheParentIdForTopLevelNode() {
    fetcher.childCandidatesFor(Dimension.CATEGORY, "5", Scope.ofTypes("expense"));

    verify(queryRepository).childAccountCandidates(5L, true);
  }

  @Test
  void childCandidatesForUsesLastSegmentOfCompositeKeyAsTheParentId() {
    fetcher.childCandidatesFor(Dimension.CATEGORY, "1|10", Scope.ofTypes("expense"));

    verify(queryRepository).childAccountCandidates(10L, true);
  }

  @Test
  void childCandidatesForDegradesToNoChildrenForMalformedTrailingSegmentInsteadOfThrowing() {
    // A stage e2 toggle endpoint's `node` request param, or a persisted expandedNodeKeys entry, is
    // hand-editable request input — a malformed one (or Tag's own non-numeric "<id>:unspecified"
    // leaf key, which is never itself expandable and so should never legitimately arrive here as a
    // parent) must degrade to "no children", not throw and crash the whole render.
    List<TopLevelNode> children =
        fetcher.childCandidatesFor(Dimension.CATEGORY, "1|abc", Scope.ofTypes("expense"));

    assertThat(children).isEmpty();
    verify(queryRepository).childAccountCandidates(-1L, true);
  }

  @Test
  void childCandidatesForOnTagDimensionAlsoDegradesGracefullyForMalformedTrailingSegment() {
    List<TopLevelNode> children =
        fetcher.childCandidatesFor(Dimension.TAG, "5:unspecified", Scope.ofTypes("expense"));

    assertThat(children).isEmpty();
    verify(queryRepository).childTagCandidates(-1L);
  }
}
