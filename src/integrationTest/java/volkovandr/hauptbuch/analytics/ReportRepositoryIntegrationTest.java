package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.analytics.repository.ReportRepository;

/**
 * Integration tier (CLAUDE.md §6): the {@code report} table round-trip (reporting.md §14, plan
 * stage d) — plain inserts/selects, so this tier rather than {@code sqlLogicTest}. Covers every
 * {@link ReportRepository} method plus the jsonb round-trip for a spec exercising every part of
 * {@link ReportSpec} (both {@link RangeEndpoint} kinds, a populated {@link Scope}, a {@link
 * ReportFilter}, and a count measure alongside the two money kinds) — {@code
 * volkovandr.hauptbuch.analytics.repository.ReportSpecJsonTest} holds the codec's own unit
 * coverage; this asserts the whole thing survives a real Postgres jsonb column.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportRepositoryIntegrationTest {

  @Autowired ReportRepository reportRepository;

  private static final ReportSpec ELABORATE_SPEC =
      new ReportSpec(
          List.of(Dimension.CATEGORY),
          List.of(Dimension.DATE),
          List.of(),
          List.of(
              Measure.turnover(PresentationCurrency.BASE, Leg.NET),
              Measure.closingBalance(PresentationCurrency.ACCOUNT),
              Measure.countTransactions()),
          new Scope(Set.of("income", "expense"), false, true),
          List.of(
              new ReportFilter(
                  FilterField.PAYEE,
                  FilterLevel.TRANSACTION,
                  FilterOperator.MATCHES,
                  List.of("(?i)shop.*"))),
          new DateRange(
              new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
              new RangeEndpoint.Relative(RangeUnit.MONTH, -1, RangeEdge.END)),
          true,
          false,
          true);

  @Test
  void insertAndFindByIdRoundTripTheSpecUnchanged() {
    SavedReport inserted =
        reportRepository.insert("My matrix", ELABORATE_SPEC, Renderer.TABLE, false);

    Optional<SavedReport> found = reportRepository.findById(inserted.reportId());

    assertThat(found).isPresent();
    assertThat(found.get().name()).isEqualTo("My matrix");
    assertThat(found.get().renderer()).isEqualTo(Renderer.TABLE);
    assertThat(found.get().trendLine()).isFalse();
    assertThat(found.get().spec()).isEqualTo(ELABORATE_SPEC);
  }

  @Test
  void findByIdIsEmptyForAnUnknownId() {
    assertThat(reportRepository.findById(999_999L)).isEmpty();
  }

  @Test
  void findAllListsEverySavedReportAlphabetically() {
    reportRepository.insert("Zebra report", Presets.balanceSheet(), Renderer.TABLE, false);
    reportRepository.insert("Alpha report", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    List<String> names = reportRepository.findAll().stream().map(SavedReport::name).toList();

    assertThat(names).containsExactly("Alpha report", "Zebra report");
  }

  @Test
  void renameChangesTheNameOnly() {
    SavedReport inserted =
        reportRepository.insert("Original name", Presets.balanceSheet(), Renderer.TABLE, false);

    reportRepository.rename(inserted.reportId(), "Renamed");

    SavedReport found = reportRepository.findById(inserted.reportId()).orElseThrow();
    assertThat(found.name()).isEqualTo("Renamed");
    assertThat(found.spec()).isEqualTo(Presets.balanceSheet());
  }

  @Test
  void deleteRemovesTheRow() {
    SavedReport inserted =
        reportRepository.insert("Disposable", Presets.balanceSheet(), Renderer.TABLE, false);

    reportRepository.delete(inserted.reportId());

    assertThat(reportRepository.findById(inserted.reportId())).isEmpty();
  }

  @Test
  void chartPresetsRoundTripTheirRendererAndTrendLine() {
    SavedReport inserted =
        reportRepository.insert("My chart", Presets.netWorthOverTime(), Renderer.LINE, true);

    SavedReport found = reportRepository.findById(inserted.reportId()).orElseThrow();
    assertThat(found.renderer()).isEqualTo(Renderer.LINE);
    assertThat(found.trendLine()).isTrue();
  }
}
