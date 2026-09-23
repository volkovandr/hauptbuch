package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportSpecQueryString}'s own encode/decode round trip — the one
 * piece whose bugs silently change a Report (reporting.md §11a.1, implementation-plan-reporting.md
 * d3). Mirrors {@link volkovandr.hauptbuch.analytics.repository.ReportSpecJsonTest}'s shapes.
 */
class ReportSpecQueryStringTest {

  @Test
  void roundTripsSpecWithEveryMeasureKindFiltersAndBothEndpointKinds() {
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(),
            List.of(
                Measure.turnover(PresentationCurrency.BASE, Leg.NET),
                Measure.closingBalance(PresentationCurrency.ACCOUNT),
                Measure.countPostings()),
            new Scope(Set.of("income", "expense"), false, true),
            List.of(
                new ReportFilter(
                    FilterField.PAYEE,
                    FilterLevel.TRANSACTION,
                    FilterOperator.MATCHES,
                    List.of("(?i)shop.*")),
                new ReportFilter(
                    FilterField.NOTE,
                    FilterLevel.POSTING,
                    FilterOperator.CONTAINS,
                    List.of("fuel")),
                new ReportFilter(
                    FilterField.CATEGORY,
                    FilterLevel.POSTING,
                    FilterOperator.IS_ONE_OF,
                    List.of("1", "2", "3"))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Relative(RangeUnit.MONTH, -1, RangeEdge.END)),
            true,
            false,
            true,
            true);

    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(spec);

    assertThat(ReportSpecQueryString.isPresent(params)).isTrue();
    assertThat(ReportSpecQueryString.fromParams(params)).isEqualTo(spec);
  }

  @Test
  void roundTripsAnEmptySpecShape() {
    ReportSpec spec =
        new ReportSpec(
            List.of(),
            List.of(),
            List.of(Dimension.DATE),
            List.of(Measure.countTransactions()),
            Scope.ofTypes(),
            List.of(),
            new DateRange(
                new RangeEndpoint.Relative(RangeUnit.YEAR, 0, RangeEdge.START),
                new RangeEndpoint.Relative(RangeUnit.DAY, 0, RangeEdge.START)),
            false,
            false,
            false);

    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(spec);

    assertThat(ReportSpecQueryString.fromParams(params)).isEqualTo(spec);
  }

  @Test
  void roundTripsWeekLadderChoice() {
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            true,
            true,
            true,
            false,
            DateLadder.WEEK);

    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(spec);

    assertThat(ReportSpecQueryString.fromParams(params)).isEqualTo(spec);
  }

  @Test
  void missingDateLadderParamDecodesToMonth() {
    // A draft URL from before stage e4 carries no dateLadder param at all — must default to
    // DateLadder.MONTH rather than throwing.
    MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    params.remove("dateLadder");

    ReportSpec decoded = ReportSpecQueryString.fromParams(params);

    assertThat(decoded.dateLadder()).isEqualTo(DateLadder.MONTH);
  }

  @Test
  void roundTripsSuppressEmptyColumnsOn() {
    ReportSpec spec =
        new ReportSpec(
            List.of(),
            List.of(Dimension.CATEGORY),
            List.of(),
            List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
            Scope.ofTypes("expense"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            true,
            true,
            true,
            false,
            DateLadder.MONTH,
            true);

    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(spec);

    assertThat(ReportSpecQueryString.fromParams(params)).isEqualTo(spec);
  }

  @Test
  void missingSuppressEmptyColumnsParamDecodesToFalse() {
    MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    params.remove("suppressEmptyColumns");

    ReportSpec decoded = ReportSpecQueryString.fromParams(params);

    assertThat(decoded.suppressEmptyColumns()).isFalse();
  }

  @Test
  void preservesMeasureOrderSinceItDrivesColumnOrder() {
    ReportSpec spec =
        new ReportSpec(
            List.of(),
            List.of(),
            List.of(),
            List.of(
                Measure.countTransactions(),
                Measure.turnover(PresentationCurrency.ACCOUNT, Leg.CREDITS),
                Measure.closingBalance(PresentationCurrency.BASE),
                Measure.countPostings()),
            Scope.ofTypes("asset"),
            List.of(),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            false,
            false,
            true);

    ReportSpec decoded = ReportSpecQueryString.fromParams(ReportSpecQueryString.toParams(spec));

    assertThat(decoded.measures()).containsExactlyElementsOf(spec.measures());
  }

  @Test
  void isNotPresentWithNoParamsAtAll() {
    assertThat(
            ReportSpecQueryString.isPresent(new org.springframework.util.LinkedMultiValueMap<>()))
        .isFalse();
  }

  @Test
  void isPresentEvenWithEveryMeasureUnticked() {
    // A settings-strip Apply with every Measures checkbox cleared (plan stage d3) submits no
    // `measure` param at all — isPresent must still say "yes, a draft" so the rest of the edit
    // (rows, scope, range, ...) is not silently discarded back to the saved/Preset spec.
    org.springframework.util.MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    params.remove("measure");

    assertThat(ReportSpecQueryString.isPresent(params)).isTrue();
  }

  private static ReportSpec nestedSpec(List<Dimension> rows, List<Dimension> columns) {
    return new ReportSpec(
        rows,
        columns,
        List.of(),
        List.of(Measure.turnover(PresentationCurrency.BASE, Leg.NET)),
        Scope.ofTypes("expense"),
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
        true,
        true,
        true);
  }

  @Test
  void roundTripsNestedRowDimension() {
    ReportSpec spec =
        nestedSpec(List.of(Dimension.TAG, Dimension.CATEGORY), List.of(Dimension.DATE));

    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(spec);

    assertThat(params.getFirst("rows")).isEqualTo("TAG");
    assertThat(params.getFirst("rowsNested")).isEqualTo("CATEGORY");
    assertThat(ReportSpecQueryString.fromParams(params)).isEqualTo(spec);
  }

  @Test
  void roundTripsNestedColumnDimension() {
    ReportSpec spec =
        nestedSpec(List.of(Dimension.DATE), List.of(Dimension.CATEGORY, Dimension.TAG));

    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(spec);

    assertThat(params.getFirst("columnsNested")).isEqualTo("TAG");
    assertThat(ReportSpecQueryString.fromParams(params)).isEqualTo(spec);
  }

  @Test
  void dropsNestedDimensionThatCannotNestUnderTheOuterOne() {
    // The live form submits the nested <select>'s old value for one request when the operator
    // changes the outer slot under it — decoding must drop it, not throw or promote it.
    MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(
            nestedSpec(List.of(Dimension.TAG, Dimension.CATEGORY), List.of(Dimension.DATE)));

    params.set("rows", "CATEGORY");
    assertThat(ReportSpecQueryString.fromParams(params).rows()).containsExactly(Dimension.CATEGORY);

    params.set("rows", "PAYEE");
    assertThat(ReportSpecQueryString.fromParams(params).rows()).containsExactly(Dimension.PAYEE);

    params.set("rows", "");
    assertThat(ReportSpecQueryString.fromParams(params).rows()).isEmpty();

    params.set("rows", "TAG");
    params.set("rowsNested", "DATE");
    params.set("columns", "");
    assertThat(ReportSpecQueryString.fromParams(params).rows()).containsExactly(Dimension.TAG);
  }

  @Test
  void decodesBlankNestedParamAsNoNestedDimension() {
    MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(
            nestedSpec(List.of(Dimension.TAG, Dimension.CATEGORY), List.of(Dimension.DATE)));
    params.set("rowsNested", "");

    assertThat(ReportSpecQueryString.fromParams(params).rows()).containsExactly(Dimension.TAG);
  }

  @Test
  void decodesBlankAxisParamAsNoDimension() {
    // The settings strip's "None" <select> option (plan stage d3) submits its own value, an empty
    // string, rather than omitting the parameter the way toParams does for an empty axis.
    org.springframework.util.MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    params.set("rows", "");

    ReportSpec decoded = ReportSpecQueryString.fromParams(params);

    assertThat(decoded.rows()).isEmpty();
  }

  @Test
  void blankLiteralEndpointDateResolvesToTodayInsteadOfThrowing() {
    // Apply submitted while the Date/Relative switch (reporting.md §11a.6) sits on Date but no
    // date was ever picked — the settings strip's own live preview degrades the same way.
    org.springframework.util.MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    params.set("rangeStart.type", "LITERAL");
    params.set("rangeStart.date", "");

    ReportSpec decoded = ReportSpecQueryString.fromParams(params);

    assertThat(decoded.range().start()).isEqualTo(new RangeEndpoint.Literal(LocalDate.now()));
  }

  @Test
  void malformedLiteralEndpointDateResolvesToTodayInsteadOfThrowing() {
    org.springframework.util.MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    params.set("rangeStart.type", "LITERAL");
    params.set("rangeStart.date", "not-a-date");

    ReportSpec decoded = ReportSpecQueryString.fromParams(params);

    assertThat(decoded.range().start()).isEqualTo(new RangeEndpoint.Literal(LocalDate.now()));
  }

  @Test
  void filterFieldWithNoTickedValueDecodesToNoFilterAtAll() {
    // The settings strip's Filters group (plan stage d3-4) always resubmits filterField/level/op
    // for every one of the nine fixed sections, ticked or not — an empty one must not throw
    // ReportFilter's own "needs at least one value" rejection (reporting.md §11a.5).
    org.springframework.util.MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());
    params.add("filterField", "PAYEE");
    params.add("filter.PAYEE.level", "TRANSACTION");
    params.add("filter.PAYEE.op", "IS_ONE_OF");

    ReportSpec decoded = ReportSpecQueryString.fromParams(params);

    assertThat(decoded.filters()).isEmpty();
  }

  @Test
  void payeeMatchesRoundTripsUnderItsOwnKeyAlongsideStaleValues() {
    // The Payee section keeps both the checkbox list (name="filter.PAYEE.value") and the regex
    // field always in the DOM (mirroring the date-range endpoint fields), so a stale/empty checkbox
    // submission must not collide with the regex's own value.
    ReportSpec spec =
        new ReportSpec(
            List.of(),
            List.of(),
            List.of(),
            List.of(Measure.countTransactions()),
            Scope.ofTypes("expense"),
            List.of(
                new ReportFilter(
                    FilterField.PAYEE,
                    FilterLevel.TRANSACTION,
                    FilterOperator.MATCHES,
                    List.of("(?i)shop.*"))),
            new DateRange(
                new RangeEndpoint.Relative(RangeUnit.YEAR, 0, RangeEdge.START),
                new RangeEndpoint.Relative(RangeUnit.DAY, 0, RangeEdge.START)),
            false,
            false,
            false);

    org.springframework.util.MultiValueMap<String, String> params =
        ReportSpecQueryString.toParams(spec);
    // A stray empty entry under the sibling checkbox-list key, as the settings strip's own
    // always-in-the-DOM checkbox list would submit with nothing ticked.
    params.add("filter.PAYEE.value", "");

    assertThat(ReportSpecQueryString.fromParams(params)).isEqualTo(spec);
  }
}
