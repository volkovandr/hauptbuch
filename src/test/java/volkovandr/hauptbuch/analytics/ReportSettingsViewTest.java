package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportSettingsView#build}, the settings strip's own view
 * (reporting.md §11a.2/§11a.3, plan stage d3) — no DB, so pure data assembly from an already-
 * resolved {@link PresetRendering.Presentation}. Rendering the strip itself is each controller's
 * own integration coverage.
 */
class ReportSettingsViewTest {

  private static ReportSettingsView.View build(
      ReportSpec spec, Renderer renderer, boolean trendLine) {
    PresetRendering.Presentation presentation =
        new PresetRendering.Presentation("Title", spec, renderer, trendLine);
    return ReportSettingsView.build(presentation, "/reports/42", LocalDate.of(2026, 6, 15));
  }

  @Test
  void rowsColumnsSelectsTheSpecsOwnDimensionsAndOffersNoneFirst() {
    ReportSettingsView.View view = build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    assertThat(view.rowsColumns().rows().get(0).value()).isEqualTo("");
    assertThat(view.rowsColumns().rows().get(0).label()).isEqualTo("None");
    ReportSettingsView.AxisOption selectedRow =
        view.rowsColumns().rows().stream()
            .filter(ReportSettingsView.AxisOption::selected)
            .findFirst()
            .orElseThrow();
    assertThat(selectedRow.value()).isEqualTo("CATEGORY");
    ReportSettingsView.AxisOption selectedColumn =
        view.rowsColumns().columns().stream()
            .filter(ReportSettingsView.AxisOption::selected)
            .findFirst()
            .orElseThrow();
    assertThat(selectedColumn.value()).isEqualTo("DATE");
  }

  @Test
  void seriesIsDisabledWhenRowsIsSetAndViceVersa() {
    ReportSettingsView.View matrixView =
        build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    assertThat(matrixView.rowsColumns().seriesDisabled()).isTrue();
    assertThat(matrixView.rowsColumns().rowsDisabled()).isFalse();

    ReportSettingsView.View seriesView = build(Presets.thisMonthVsLast(), Renderer.BAR, false);
    assertThat(seriesView.rowsColumns().rowsDisabled()).isTrue();
    assertThat(seriesView.rowsColumns().seriesDisabled()).isFalse();
  }

  @Test
  void columnsOfferOnlyNoneAndDateWhileRowsHoldsNonDateDimension() {
    // ReportEngine.validateOneNonDateDimension (§3): rows=Category, columns=Payee would throw at
    // render time — the columns dropdown must not be able to reach that state in the first place.
    ReportSettingsView.View view = build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    assertThat(view.rowsColumns().columns())
        .extracting(ReportSettingsView.AxisOption::value)
        .containsExactlyInAnyOrder("", "DATE");
  }

  @Test
  void rowsExcludesOnlyDateWhileColumnsHoldsDate() {
    // The flagship matrix (rows=Category, columns=Date) must stay fully editable: switching rows
    // to any OTHER non-Date dimension is legal, only Date itself is off-limits on rows too.
    ReportSettingsView.View view = build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    assertThat(view.rowsColumns().rows())
        .extracting(ReportSettingsView.AxisOption::value)
        .contains("", "CATEGORY", "ACCOUNT", "TAG", "PAYEE")
        .doesNotContain("DATE");
  }

  @Test
  void seriesOffersOnlyNoneAndDateWhileColumnsHoldsNonDateDimension() {
    // this-month-vs-last: rows=[], columns=Category, series=Date. Series fills the row slot, so it
    // must be excluded the same way rows would be while columns holds Category.
    ReportSettingsView.View view = build(Presets.thisMonthVsLast(), Renderer.BAR, false);

    assertThat(view.rowsColumns().series())
        .extracting(ReportSettingsView.AxisOption::value)
        .containsExactlyInAnyOrder("", "DATE");
  }

  @Test
  void rowsColumnsOtherParamsExcludesItsOwnThreeKeysButKeepsTheRest() {
    ReportSettingsView.View view = build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    MultiValueMap<String, String> other = view.rowsColumns().otherParams();

    assertThat(other).doesNotContainKeys("rows", "columns", "series");
    assertThat(other).containsKey("measure");
    assertThat(other.getFirst("renderer")).isEqualTo("TABLE");
  }

  @Test
  void measuresGridTicksExactlyTheSpecsOwnMeasuresInFixedOrder() {
    ReportSettingsView.View view = build(Presets.balanceSheet(), Renderer.TABLE, false);

    List<ReportSettingsView.MeasureRow> rows = view.measures().rows();
    assertThat(rows).hasSize(6);
    // Balance sheet: closing balance in both base and account currency, nothing else.
    ReportSettingsView.MeasureRow closingBalance = rows.get(3);
    assertThat(closingBalance.label()).isEqualTo("Closing balance");
    assertThat(closingBalance.base().checked()).isTrue();
    assertThat(closingBalance.account().checked()).isTrue();
    assertThat(rows.get(0).base().checked()).isFalse();
    // Count rows carry no currency variant.
    assertThat(rows.get(4).hasCurrencies()).isFalse();
    assertThat(rows.get(4).account()).isNull();
  }

  @Test
  void scopeTypesReflectTheSpecsOwnAccountTypesAlphabetically() {
    ReportSettingsView.View view = build(Presets.balanceSheet(), Renderer.TABLE, false);

    List<ReportSettingsView.ScopeTypeOption> types = view.scope().types();
    assertThat(types)
        .extracting(ReportSettingsView.ScopeTypeOption::value)
        .containsExactly("asset", "equity", "expense", "income", "liability");
    assertThat(types)
        .filteredOn(ReportSettingsView.ScopeTypeOption::checked)
        .extracting(ReportSettingsView.ScopeTypeOption::value)
        .containsExactlyInAnyOrder("asset", "equity", "liability");
  }

  @Test
  void scopeToggleOtherParamsExcludeOnlyTheTwoToggleKeys() {
    ReportSettingsView.View view = build(Presets.balanceSheet(), Renderer.TABLE, false);

    assertThat(view.scope().togglesOtherParams())
        .doesNotContainKeys("includeClosed", "includePending");
    assertThat(view.scope().typesOtherParams()).doesNotContainKey("scopeType");
    assertThat(view.scope().typesOtherParams()).containsKey("includeClosed");
  }

  @Test
  void dateRangeShortcutsCarryTheRestOfTheStateAndTheirOwnResolvedRange() {
    ReportSettingsView.View view = build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    ReportSettingsView.Shortcut yearToDate = view.dateRange().shortcuts().get(0);
    assertThat(yearToDate.label()).isEqualTo("Year to date");
    assertThat(yearToDate.url()).startsWith("/reports/42?");
    assertThat(yearToDate.url()).contains("measure=");
    assertThat(yearToDate.url()).contains("rangeStart.unit=YEAR");
  }

  @Test
  void endpointFieldsResolveLiteralDateAgainstToday() {
    ReportSpec spec = Presets.balanceSheet(); // both endpoints are day,0,start relative
    ReportSettingsView.View view = build(spec, Renderer.TABLE, false);

    assertThat(view.dateRange().start().literal()).isFalse();
    assertThat(view.dateRange().start().resolvedLabel()).isEqualTo("= 15.06.2026");
  }

  @Test
  void displayReflectsTheSpecsOwnTotalsAndSuppressionFlags() {
    ReportSettingsView.View view = build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    assertThat(view.display().rowTotals()).isTrue();
    assertThat(view.display().columnTotals()).isTrue();
    assertThat(view.display().suppressEmptyRows()).isTrue();
    assertThat(view.display().groupHeaderParents()).isFalse();
  }

  @Test
  void rendererGroupShowsTrendLineOnlyForLine() {
    ReportSettingsView.View lineView = build(Presets.netWorthOverTime(), Renderer.LINE, true);
    assertThat(lineView.renderer().showTrendLine()).isTrue();
    assertThat(lineView.renderer().trendLine()).isTrue();

    ReportSettingsView.View tableView = build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    assertThat(tableView.renderer().showTrendLine()).isFalse();
  }
}
