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

  private static final Measure CLOSING_BALANCE = Measure.closingBalance(PresentationCurrency.BASE);
  private static final Measure NET_TURNOVER = Measure.turnover(PresentationCurrency.BASE, Leg.NET);

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
    // ReportEngine.refusal (§3): rows=Category, columns=Payee is refused at
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

  private static ReportSpec withAxes(List<Dimension> rows, List<Dimension> columns) {
    ReportSpec spec = Presets.categoryMonthMatrix();
    return new ReportSpec(
        rows,
        columns,
        List.of(),
        spec.measures(),
        spec.scope(),
        spec.filters(),
        spec.range(),
        spec.rowTotals(),
        spec.columnTotals(),
        spec.suppressEmptyRows());
  }

  @Test
  void nestedRowsOffersEveryOtherNonDateDimensionAndSelectsTheSpecsOwn() {
    // §3: rows = [Tag, Category] — the second slot nests under the first, so it can be neither
    // Date (the ladder fills an axis alone, §8.2) nor the outer dimension itself.
    ReportSettingsView.View view =
        build(
            withAxes(List.of(Dimension.TAG, Dimension.CATEGORY), List.of(Dimension.DATE)),
            Renderer.TABLE,
            false);

    assertThat(view.rowsColumns().rowsNestedDisabled()).isFalse();
    assertThat(view.rowsColumns().rowsNested())
        .extracting(ReportSettingsView.AxisOption::value)
        .contains("", "CATEGORY", "ACCOUNT", "PAYEE")
        .doesNotContain("TAG", "DATE");
    assertThat(view.rowsColumns().rowsNested())
        .filteredOn(ReportSettingsView.AxisOption::selected)
        .extracting(ReportSettingsView.AxisOption::value)
        .containsExactly("CATEGORY");
  }

  @Test
  void nestedSlotIsDisabledUnlessItsOuterSlotHoldsHierarchicalDimension() {
    // §9.1: only a hierarchy (Category, Account, Tag) expands to reveal a nested breakdown.
    ReportSettingsView.View payeeRows =
        build(withAxes(List.of(Dimension.PAYEE), List.of(Dimension.DATE)), Renderer.TABLE, false);
    assertThat(payeeRows.rowsColumns().rowsNestedDisabled()).isTrue();
    // Columns holds Date — the ladder never nests anything.
    assertThat(payeeRows.rowsColumns().columnsNestedDisabled()).isTrue();

    ReportSettingsView.View noRows =
        build(withAxes(List.of(), List.of(Dimension.ACCOUNT)), Renderer.TABLE, false);
    assertThat(noRows.rowsColumns().rowsNestedDisabled()).isTrue();
    assertThat(noRows.rowsColumns().columnsNestedDisabled()).isFalse();
  }

  private static ReportSpec withAxesAndMeasure(
      List<Dimension> rows, List<Dimension> columns, List<Dimension> series, Measure measure) {
    ReportSpec spec = Presets.categoryMonthMatrix();
    return new ReportSpec(
        rows,
        columns,
        series,
        List.of(measure),
        spec.scope(),
        spec.filters(),
        spec.range(),
        spec.rowTotals(),
        spec.columnTotals(),
        spec.suppressEmptyRows());
  }

  private static ReportSettingsView.MeasureRow closingBalanceRow(ReportSettingsView.View view) {
    return view.measures().rows().stream()
        .filter(row -> "Closing balance".equals(row.label()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void closingBalanceKeepsTagAndPayeeOffEveryAxisSlot() {
    // Issue 18: a tag or payee holds no balance (reporting.md §4) — the engine refuses the pair,
    // so the form must not offer it in any slot, outer or nested.
    ReportSettingsView.View rowsView =
        build(
            withAxesAndMeasure(
                List.of(Dimension.ACCOUNT), List.of(Dimension.DATE), List.of(), CLOSING_BALANCE),
            Renderer.TABLE,
            false);
    ReportSettingsView.View columnsView =
        build(
            withAxesAndMeasure(
                List.of(Dimension.DATE), List.of(Dimension.ACCOUNT), List.of(), CLOSING_BALANCE),
            Renderer.TABLE,
            false);
    ReportSettingsView.View seriesView =
        build(
            withAxesAndMeasure(
                List.of(), List.of(Dimension.DATE), List.of(Dimension.ACCOUNT), CLOSING_BALANCE),
            Renderer.LINE,
            false);

    for (List<ReportSettingsView.AxisOption> options :
        List.of(
            rowsView.rowsColumns().rows(),
            rowsView.rowsColumns().rowsNested(),
            columnsView.rowsColumns().columns(),
            columnsView.rowsColumns().columnsNested(),
            seriesView.rowsColumns().series())) {
      assertThat(options)
          .extracting(ReportSettingsView.AxisOption::value)
          .contains("CATEGORY")
          .doesNotContain("TAG", "PAYEE");
    }
  }

  @Test
  void turnoverStillOffersTagAndPayee() {
    ReportSettingsView.View view =
        build(
            withAxesAndMeasure(
                List.of(Dimension.ACCOUNT), List.of(Dimension.DATE), List.of(), NET_TURNOVER),
            Renderer.TABLE,
            false);

    assertThat(view.rowsColumns().rows())
        .extracting(ReportSettingsView.AxisOption::value)
        .contains("TAG", "PAYEE");
  }

  @Test
  void illegalTagAlreadyOnAxisStaysSelectableSoTheOperatorCanChangeIt() {
    // A hand-typed URL can still arrive with the refused pair; the dropdown must show what is
    // there, or the operator cannot see which choice to undo.
    ReportSettingsView.View view =
        build(
            withAxesAndMeasure(
                List.of(Dimension.TAG), List.of(Dimension.DATE), List.of(), CLOSING_BALANCE),
            Renderer.TABLE,
            false);

    assertThat(view.rowsColumns().rows())
        .filteredOn(ReportSettingsView.AxisOption::selected)
        .extracting(ReportSettingsView.AxisOption::value)
        .containsExactly("TAG");
    assertThat(view.rowsColumns().rows())
        .extracting(ReportSettingsView.AxisOption::value)
        .doesNotContain("PAYEE");
  }

  @Test
  void closingBalanceIsUnavailableWhileTagOrPayeeSitsInAnySlot() {
    for (ReportSpec spec :
        List.of(
            withAxesAndMeasure(
                List.of(Dimension.PAYEE), List.of(Dimension.DATE), List.of(), NET_TURNOVER),
            withAxesAndMeasure(
                List.of(Dimension.CATEGORY, Dimension.TAG),
                List.of(Dimension.DATE),
                List.of(),
                NET_TURNOVER),
            withAxesAndMeasure(
                List.of(), List.of(Dimension.DATE), List.of(Dimension.TAG), NET_TURNOVER))) {
      ReportSettingsView.View view = build(spec, Renderer.TABLE, false);

      ReportSettingsView.MeasureRow closingBalance = closingBalanceRow(view);
      assertThat(closingBalance.unavailableReason()).isNotBlank();
      assertThat(closingBalance.base().disabled()).isTrue();
      assertThat(closingBalance.account().disabled()).isTrue();
      ReportSettingsView.MeasureRow netTurnover = view.measures().rows().get(0);
      assertThat(netTurnover.unavailableReason()).isNull();
      assertThat(netTurnover.base().disabled()).isFalse();
    }
  }

  @Test
  void tickedClosingBalanceStaysEnabledSoTheOperatorCanUntickIt() {
    ReportSettingsView.View view =
        build(
            withAxesAndMeasure(
                List.of(Dimension.TAG), List.of(Dimension.DATE), List.of(), CLOSING_BALANCE),
            Renderer.TABLE,
            false);

    ReportSettingsView.MeasureRow closingBalance = closingBalanceRow(view);
    assertThat(closingBalance.base().checked()).isTrue();
    assertThat(closingBalance.base().disabled()).isFalse();
    assertThat(closingBalance.account().disabled()).isTrue();
  }

  @Test
  void closingBalanceIsAvailableWithoutTagOrPayee() {
    ReportSettingsView.View view = build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    ReportSettingsView.MeasureRow closingBalance = closingBalanceRow(view);
    assertThat(closingBalance.unavailableReason()).isNull();
    assertThat(closingBalance.base().disabled()).isFalse();
  }

  @Test
  void rowsColumnsOtherParamsExcludesItsOwnThreeKeysButKeepsTheRest() {
    ReportSettingsView.View view = build(Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    MultiValueMap<String, String> other = view.rowsColumns().otherParams();

    assertThat(other)
        .doesNotContainKeys("rows", "rowsNested", "columns", "columnsNested", "series");
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
    assertThat(view.display().suppressEmptyColumns()).isFalse();
    assertThat(view.display().groupHeaderParents()).isFalse();
    assertThat(view.display().dateLadder()).isEqualTo("MONTH");
  }

  @Test
  void displayReflectsTheSpecsOwnWeekLadderChoice() {
    ReportSpec weekLadder =
        new ReportSpec(
            Presets.categoryMonthMatrix().rows(),
            Presets.categoryMonthMatrix().columns(),
            Presets.categoryMonthMatrix().series(),
            Presets.categoryMonthMatrix().measures(),
            Presets.categoryMonthMatrix().scope(),
            Presets.categoryMonthMatrix().filters(),
            Presets.categoryMonthMatrix().range(),
            Presets.categoryMonthMatrix().rowTotals(),
            Presets.categoryMonthMatrix().columnTotals(),
            Presets.categoryMonthMatrix().suppressEmptyRows(),
            false,
            DateLadder.WEEK);
    ReportSettingsView.View view = build(weekLadder, Renderer.TABLE, false);

    assertThat(view.display().dateLadder()).isEqualTo("WEEK");
  }

  @Test
  void displayReflectsTheSpecsOwnSuppressEmptyColumnsChoice() {
    ReportSpec spec = Presets.categoryMonthMatrix();
    ReportSpec suppressColumns =
        new ReportSpec(
            spec.rows(),
            spec.columns(),
            spec.series(),
            spec.measures(),
            spec.scope(),
            spec.filters(),
            spec.range(),
            spec.rowTotals(),
            spec.columnTotals(),
            spec.suppressEmptyRows(),
            spec.groupHeaderParents(),
            spec.dateLadder(),
            true);
    ReportSettingsView.View view = build(suppressColumns, Renderer.TABLE, false);

    assertThat(view.display().suppressEmptyColumns()).isTrue();
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
