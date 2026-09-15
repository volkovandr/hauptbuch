package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The settings strip's own view (reporting.md §11a.2/§11a.3, plan stage d3): Rows &amp; columns,
 * Measures, Scope, Date range, Display, and the always-visible Renderer — everything the engine
 * supports today except Filters, which is {@link ReportFilterView}/{@link
 * ReportFilterViewAssembler}'s own (plan stage d3-4) since it needs DB reads this class does not.
 * Built once per render from the page's own effective {@link PresetRendering.Presentation} and
 * shared by the table and chart editor pages ({@code fragments/report-settings.html}).
 *
 * <p>Each group is its own {@code <form>}: hidden fields carry the REST of {@link
 * PresetRendering#allParams} (every field this group does not itself own), alongside the group's
 * own real, named inputs — so any group's request always resubmits the whole editable state, never
 * a partial one that would silently reset the rest. Live groups (Rows &amp; columns, Display, the
 * Renderer) fire on {@code change}; Apply groups (Measures, Scope's account types, the date range's
 * endpoint editors) carry their own Apply button and fire on the form's default {@code submit}
 * trigger, which degrades to a plain GET reload with htmx off — the same idiom the register's own
 * filter Apply already uses.
 */
// CouplingBetweenObjects: this class's whole job is turning every ReportSpec vocabulary type
// (Dimension, Measure, MeasureKind, Leg, PresentationCurrency, Scope, DateRange, RangeEndpoint,
// RangeUnit, RangeEdge, Renderer, ...) into the settings strip's view — a mapping step, the same
// shape ReportGridBuilder and ReportDataFetcher are already suppressed for, and for the same
// reason: splitting it would still leave every piece referencing the same small spec records.
@SuppressWarnings("PMD.CouplingBetweenObjects")
final class ReportSettingsView {

  /**
   * Alphabetical, matching {@link ScopeHeaderText}'s own ordering of the same five values. Reused
   * by {@link ReportFilterViewAssembler} for the Filters group's Account type section, which offers
   * the identical five values (plan stage d3-4).
   */
  static final List<String> ACCOUNT_TYPES =
      List.of("asset", "equity", "expense", "income", "liability");

  private static final String[] RANGE_KEYS = {
    "rangeStart.type",
    "rangeStart.date",
    "rangeStart.unit",
    "rangeStart.offset",
    "rangeStart.edge",
    "rangeEnd.type",
    "rangeEnd.date",
    "rangeEnd.unit",
    "rangeEnd.offset",
    "rangeEnd.edge"
  };

  private ReportSettingsView() {}

  /** One {@code <option>} of a Rows/Columns/Series dropdown — {@code value} {@code ""} is None. */
  record AxisOption(String value, String label, boolean selected) {}

  /**
   * Rows, columns and series share one form (reporting.md §11a.2's "one dropdown per axis slot"):
   * none of the three needs its own Apply, and rows/series' mutual exclusion (§3, {@link
   * ReportSpec}'s own constructor) is made unenterable by disabling whichever dropdown the other
   * side has already claimed, rather than by a JS-driven auto-clear.
   */
  record RowsColumns(
      List<AxisOption> rows,
      boolean rowsDisabled,
      List<AxisOption> columns,
      List<AxisOption> series,
      boolean seriesDisabled,
      MultiValueMap<String, String> otherParams) {}

  /** One checkbox of the Measures grid (§11a.4) — {@code null} where a cell has no currency. */
  record MeasureCell(String value, boolean checked) {}

  record MeasureRow(String label, MeasureCell base, MeasureCell account, boolean hasCurrencies) {}

  record Measures(List<MeasureRow> rows, MultiValueMap<String, String> otherParams) {}

  record ScopeTypeOption(String value, String label, boolean checked) {}

  /**
   * Scope's account types are a checkbox group (Apply); the two toggles are independent booleans,
   * each a complete decision on its own, so both are live (§11a.3) — hence the two separate {@code
   * otherParams} maps, one per sub-form.
   */
  record Scope(
      List<ScopeTypeOption> types,
      MultiValueMap<String, String> typesOtherParams,
      boolean includeClosed,
      boolean includePendingReview,
      MultiValueMap<String, String> togglesOtherParams) {}

  /** A named date-range shortcut (§11a.6) — a plain link, since it needs no Apply of its own. */
  record Shortcut(String label, String url) {}

  /**
   * One endpoint's editor (§11a.6): both the literal date field and the unit/offset/edge fields are
   * always present and always submitted — only {@code literal} (the Date/Relative switch, a
   * CSS-only show/hide, reporting.md's ⓘ-adjacent radio pair) decides which one {@link
   * ReportSpecQueryString} actually reads on decode, so there is no risk in rendering both.
   */
  record EndpointFields(
      String fieldPrefix,
      boolean literal,
      String date,
      String unit,
      int offset,
      String edge,
      String resolvedLabel) {}

  record DateRangeGroup(
      List<Shortcut> shortcuts,
      EndpointFields start,
      EndpointFields end,
      MultiValueMap<String, String> otherParams) {}

  record Display(
      boolean rowTotals,
      boolean columnTotals,
      boolean suppressEmptyRows,
      MultiValueMap<String, String> otherParams) {}

  /** {@code showTrendLine} is true only for {@link Renderer#LINE} (reporting.md §11a.2). */
  record RendererGroup(
      String renderer,
      boolean trendLine,
      boolean showTrendLine,
      MultiValueMap<String, String> otherParams) {}

  record View(
      String pagePath,
      RowsColumns rowsColumns,
      Measures measures,
      Scope scope,
      DateRangeGroup dateRange,
      Display display,
      RendererGroup renderer) {}

  static View build(PresetRendering.Presentation effective, String pagePath, LocalDate today) {
    ReportSpec spec = effective.spec();
    MultiValueMap<String, String> all = PresetRendering.allParams(effective);
    return new View(
        pagePath,
        rowsColumns(spec, all),
        measures(spec, all),
        scope(spec.scope(), all),
        dateRange(spec.range(), pagePath, all, today),
        display(spec, all),
        renderer(effective, all));
  }

  private static RowsColumns rowsColumns(ReportSpec spec, MultiValueMap<String, String> all) {
    boolean rowsSet = !spec.rows().isEmpty();
    boolean seriesSet = !spec.series().isEmpty();
    Dimension row = rowsSet ? spec.rows().get(0) : null;
    Dimension column = spec.columns().isEmpty() ? null : spec.columns().get(0);
    Dimension series = seriesSet ? spec.series().get(0) : null;
    // ReportEngine.validateOneNonDateDimension (stage a/d3's own cap, §3): at most one axis may
    // carry a non-Date dimension, and Date cannot sit on both. Series fills the row slot when rows
    // is empty (ReportEngine.rowSlotDimension), so columns' own exclusions read that effective
    // value, not rows directly — otherwise "rows empty, series = Category, columns = Payee" would
    // stay enterable even though it is exactly this same illegal combination.
    Dimension effectiveRowSlot = rowsSet ? row : series;
    return new RowsColumns(
        axisOptionsExcluding(row, column),
        seriesSet,
        axisOptionsExcluding(column, effectiveRowSlot),
        axisOptionsExcluding(series, column),
        rowsSet,
        without(all, "rows", "columns", "series"));
  }

  /**
   * This axis's own options, with every choice {@code other} has already made illegal removed — the
   * mechanism that keeps the rows/columns nesting cap (see {@link #rowsColumns}) unenterable rather
   * than a server-side rejection after the fact.
   */
  private static List<AxisOption> axisOptionsExcluding(Dimension selected, Dimension other) {
    List<AxisOption> options = new ArrayList<>();
    options.add(new AxisOption("", "None", selected == null));
    for (Dimension dimension : Dimension.values()) {
      if (excludedBy(dimension, other)) {
        continue;
      }
      options.add(
          new AxisOption(dimension.name(), dimensionLabel(dimension), dimension == selected));
    }
    return options;
  }

  private static boolean excludedBy(Dimension candidate, Dimension other) {
    if (other == null) {
      return false;
    }
    // other already claimed Date: only Date itself is off-limits here. other already claimed a
    // non-Date dimension: only Date remains legal alongside it, so every non-Date choice is out.
    return other == Dimension.DATE ? candidate == Dimension.DATE : candidate != Dimension.DATE;
  }

  private static String dimensionLabel(Dimension dimension) {
    return switch (dimension) {
      case CATEGORY -> "Category";
      case ACCOUNT -> "Account";
      case TAG -> "Tag";
      case PAYEE -> "Payee";
      case PERSON -> "Person";
      case CURRENCY -> "Currency";
      case ACCOUNT_TYPE -> "Account type";
      case DATE -> "Date";
    };
  }

  private static Measures measures(ReportSpec spec, MultiValueMap<String, String> all) {
    Set<Measure> ticked = new LinkedHashSet<>(spec.measures());
    List<MeasureRow> rows =
        List.of(
            turnoverRow("Turnover — net", Leg.NET, ticked),
            turnoverRow("Turnover — debits", Leg.DEBITS, ticked),
            turnoverRow("Turnover — credits", Leg.CREDITS, ticked),
            closingBalanceRow(ticked),
            countRow("Count of postings", Measure.countPostings(), ticked),
            countRow("Count of transactions", Measure.countTransactions(), ticked));
    return new Measures(rows, without(all, "measure"));
  }

  private static MeasureRow turnoverRow(String label, Leg leg, Set<Measure> ticked) {
    Measure base = Measure.turnover(PresentationCurrency.BASE, leg);
    Measure account = Measure.turnover(PresentationCurrency.ACCOUNT, leg);
    return new MeasureRow(label, measureCell(base, ticked), measureCell(account, ticked), true);
  }

  private static MeasureRow closingBalanceRow(Set<Measure> ticked) {
    Measure base = Measure.closingBalance(PresentationCurrency.BASE);
    Measure account = Measure.closingBalance(PresentationCurrency.ACCOUNT);
    return new MeasureRow(
        "Closing balance", measureCell(base, ticked), measureCell(account, ticked), true);
  }

  private static MeasureRow countRow(String label, Measure measure, Set<Measure> ticked) {
    return new MeasureRow(label, measureCell(measure, ticked), null, false);
  }

  private static MeasureCell measureCell(Measure measure, Set<Measure> ticked) {
    return new MeasureCell(ReportSpecQueryString.measureToken(measure), ticked.contains(measure));
  }

  private static Scope scope(
      volkovandr.hauptbuch.analytics.Scope scope, MultiValueMap<String, String> all) {
    List<ScopeTypeOption> types =
        ACCOUNT_TYPES.stream()
            .map(
                type ->
                    new ScopeTypeOption(
                        type, capitalize(type), scope.accountTypes().contains(type)))
            .toList();
    return new Scope(
        types,
        without(all, "scopeType"),
        scope.includeClosedAccounts(),
        scope.includePendingReview(),
        without(all, "includeClosed", "includePending"));
  }

  /**
   * Reused by {@link ReportFilterViewAssembler} for label text of the same shape (plan stage d3-4).
   */
  static String capitalize(String value) {
    return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
  }

  private static DateRangeGroup dateRange(
      DateRange range, String pagePath, MultiValueMap<String, String> all, LocalDate today) {
    MultiValueMap<String, String> otherParams = without(all, RANGE_KEYS);
    List<Shortcut> shortcuts =
        List.of(
            shortcut("Year to date", DateRange.yearToDate(), pagePath, otherParams),
            shortcut("Last 12 months", DateRange.last12Months(), pagePath, otherParams),
            shortcut("Previous month", DateRange.previousMonth(), pagePath, otherParams),
            shortcut("Previous year", DateRange.previousYear(), pagePath, otherParams),
            shortcut("Current month", DateRange.currentMonth(), pagePath, otherParams));
    return new DateRangeGroup(
        shortcuts,
        endpointFields(range.start(), "rangeStart.", today),
        endpointFields(range.end(), "rangeEnd.", today),
        otherParams);
  }

  private static Shortcut shortcut(
      String label, DateRange range, String pagePath, MultiValueMap<String, String> otherParams) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromPath(pagePath).queryParams(otherParams);
    builder.queryParams(ReportSpecQueryString.rangeParams(range));
    return new Shortcut(label, builder.build().encode().toUriString());
  }

  private static EndpointFields endpointFields(
      RangeEndpoint endpoint, String prefix, LocalDate today) {
    String resolvedLabel = RangeResolver.resolvedLabel(endpoint, today);
    if (endpoint instanceof RangeEndpoint.Literal literal) {
      return new EndpointFields(
          prefix,
          true,
          literal.date().toString(),
          RangeUnit.MONTH.name(),
          0,
          RangeEdge.START.name(),
          resolvedLabel);
    }
    RangeEndpoint.Relative relative = (RangeEndpoint.Relative) endpoint;
    return new EndpointFields(
        prefix,
        false,
        "",
        relative.unit().name(),
        relative.offset(),
        relative.edge().name(),
        resolvedLabel);
  }

  private static Display display(ReportSpec spec, MultiValueMap<String, String> all) {
    return new Display(
        spec.rowTotals(),
        spec.columnTotals(),
        spec.suppressEmptyRows(),
        without(all, "rowTotals", "columnTotals", "suppressEmptyRows"));
  }

  private static RendererGroup renderer(
      PresetRendering.Presentation effective, MultiValueMap<String, String> all) {
    return new RendererGroup(
        effective.renderer().name(),
        effective.trendLine(),
        effective.renderer() == Renderer.LINE,
        without(all, "renderer", "trendLine"));
  }

  private static MultiValueMap<String, String> without(
      MultiValueMap<String, String> params, String... keys) {
    MultiValueMap<String, String> copy = new LinkedMultiValueMap<>(params);
    for (String key : keys) {
      copy.remove(key);
    }
    return copy;
  }
}
