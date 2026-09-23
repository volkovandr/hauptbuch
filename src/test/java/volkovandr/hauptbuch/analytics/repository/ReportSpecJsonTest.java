package volkovandr.hauptbuch.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.DateLadder;
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
                    List.of("fuel"))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Relative(RangeUnit.MONTH, -1, RangeEdge.END)),
            true,
            false,
            true,
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

  // ── expanded node keys (plan stage e2) ──────────────────────────────────────────────────────

  @Test
  void fromJsonDefaultsGroupHeaderParentsToFalseWhenTheKeyIsMissingEntirely() {
    // A report.spec row saved before stage e3 has no "groupHeaderParents" key at all — decoding it
    // must default to false (subtotals, the pre-e3 behavior), not throw a NullPointerException.
    ReportSpec preStageE3 =
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
            true);
    String jsonMissingTheNewKey =
        ReportSpecJson.toJson(preStageE3).replace(",\"groupHeaderParents\":false", "");

    ReportSpec decoded = ReportSpecJson.fromJson(jsonMissingTheNewKey);

    assertThat(decoded.groupHeaderParents()).isFalse();
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

    ReportSpec decoded = ReportSpecJson.fromJson(ReportSpecJson.toJson(spec));

    assertThat(decoded.dateLadder()).isEqualTo(DateLadder.WEEK);
    assertThat(decoded).isEqualTo(spec);
  }

  @Test
  void fromJsonDefaultsDateLadderToMonthWhenTheKeyIsMissingEntirely() {
    // A report.spec row saved before stage e4 has no "dateLadder" key at all — decoding it must
    // default to DateLadder.MONTH (every such Report's own pre-e4 rendering), not throw.
    ReportSpec preStageE4 =
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
            true);
    String jsonMissingTheNewKey =
        ReportSpecJson.toJson(preStageE4).replace(",\"dateLadder\":\"MONTH\"", "");

    ReportSpec decoded = ReportSpecJson.fromJson(jsonMissingTheNewKey);

    assertThat(decoded.dateLadder()).isEqualTo(DateLadder.MONTH);
  }

  @Test
  void nullExpandedNodeKeysStaysNullBothWays() {
    assertThat(ReportSpecJson.toNodeKeysJson(null)).isNull();
    assertThat(ReportSpecJson.fromNodeKeysJson(null)).isNull();
  }

  @Test
  void roundTripsAnEmptyExpandedNodeKeySetAsDistinctFromNull() {
    String json = ReportSpecJson.toNodeKeysJson(Set.of());

    assertThat(json).isNotNull();
    assertThat(ReportSpecJson.fromNodeKeysJson(json)).isEmpty();
  }

  @Test
  void roundTripsPopulatedExpandedNodeKeys() {
    Set<String> keys = Set.of("1", "1|10", "personal:EUR");

    String json = ReportSpecJson.toNodeKeysJson(keys);

    assertThat(ReportSpecJson.fromNodeKeysJson(json)).isEqualTo(keys);
  }
}
