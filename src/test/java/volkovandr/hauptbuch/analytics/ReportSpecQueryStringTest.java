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
  void isNotPresentWithNoMeasureParam() {
    assertThat(
            ReportSpecQueryString.isPresent(new org.springframework.util.LinkedMultiValueMap<>()))
        .isFalse();
  }
}
