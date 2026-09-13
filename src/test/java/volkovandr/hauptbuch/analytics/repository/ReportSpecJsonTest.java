package volkovandr.hauptbuch.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.DateRange;
import volkovandr.hauptbuch.analytics.Dimension;
import volkovandr.hauptbuch.analytics.FilterField;
import volkovandr.hauptbuch.analytics.FilterLevel;
import volkovandr.hauptbuch.analytics.FilterOperator;
import volkovandr.hauptbuch.analytics.Leg;
import volkovandr.hauptbuch.analytics.Measure;
import volkovandr.hauptbuch.analytics.PresentationCurrency;
import volkovandr.hauptbuch.analytics.RangeEdge;
import volkovandr.hauptbuch.analytics.RangeEndpoint;
import volkovandr.hauptbuch.analytics.RangeUnit;
import volkovandr.hauptbuch.analytics.ReportFilter;
import volkovandr.hauptbuch.analytics.ReportSpec;
import volkovandr.hauptbuch.analytics.Scope;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportSpecJson}'s own encode/decode round trip, with no DB
 * involved — {@link volkovandr.hauptbuch.analytics.ReportRepositoryIntegrationTest} additionally
 * proves the same spec survives a real Postgres {@code jsonb} column.
 */
class ReportSpecJsonTest {

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
            new Scope(Set.of("income", "expense"), List.of(11L, 22L), false, true),
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
                    List.of("fuel"))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Relative(RangeUnit.MONTH, -1, RangeEdge.END)),
            true,
            false,
            true);

    String json = ReportSpecJson.toJson(spec);
    ReportSpec decoded = ReportSpecJson.fromJson(json);

    assertThat(decoded).isEqualTo(spec);
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

    assertThat(ReportSpecJson.fromJson(ReportSpecJson.toJson(spec))).isEqualTo(spec);
  }
}
