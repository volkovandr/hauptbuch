package volkovandr.hauptbuch.analytics;

import java.util.Optional;
import org.springframework.ui.Model;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Renders a Preset's spec as its table or chart view (reporting.md §10/§16) — the shared middle of
 * {@link ReportController} (a Preset's own page, with its chart/table swap), {@link
 * SavedReportController} (a saved Report's own page, plan stage d, the same swap), {@link
 * MainFrameController} (a Preset shown in the main page's Frame, with no swap), and {@link
 * ReportsLayoutController} (a Preset shown in one of the reporting page's Frames), so none of the
 * four can silently drift on how a Report renders.
 */
final class PresetRendering {

  private static final String NO_BASE_CURRENCY_VIEW = "report-unavailable";

  private PresetRendering() {}

  /**
   * The (title, spec, renderer, trendLine) tuple every renderable Report carries, whether it is a
   * {@link PresetDef} (code-defined, keyed by slug) or a {@link SavedReport} (persisted, keyed by
   * id) — collecting it into one type keeps {@link #populate} and {@link #renderOwnPage} from
   * ballooning into a long-parameter-list smell as a second caller (plan stage d) joined the first.
   */
  record Presentation(String title, ReportSpec spec, Renderer renderer, boolean trendLine) {

    static Presentation of(PresetDef def) {
      return new Presentation(def.title(), def.spec(), def.renderer(), def.trendLine());
    }

    static Presentation of(SavedReport saved) {
      return new Presentation(saved.name(), saved.spec(), saved.renderer(), saved.trendLine());
    }
  }

  /** Either {@link #report} (a table) or {@link #chart}, per the caller's {@code asTable}. */
  record Rendered(ReportTableView report, ChartView chart) {}

  /** Renders {@code presentation} as its table or chart view, per {@code asTable}. */
  static Rendered populate(
      Presentation presentation, String baseCurrency, boolean asTable, ReportEngine reportEngine) {
    ReportGrid grid = reportEngine.render(presentation.spec());
    if (asTable) {
      return new Rendered(
          ReportTableViewAssembler.assemble(
              presentation.title(), presentation.spec(), grid, baseCurrency),
          null);
    }
    return new Rendered(
        null,
        ChartViewAssembler.assemble(
            presentation.title(),
            presentation.spec(),
            grid,
            baseCurrency,
            presentation.renderer(),
            presentation.trendLine()));
  }

  /**
   * Renders {@code presentation} as a full "own page" — table or chart, per {@code asTable}, or the
   * base-currency prompt when the book has none yet — populating {@code model} exactly as {@link
   * ReportController} and {@link SavedReportController} both need. Pulled out of the two
   * controllers themselves (plan stage d) so the base-currency branch and the report/chart/hasChart
   * model wiring live in exactly one place, matching this class's own reason for existing.
   */
  static String renderOwnPage(
      Presentation presentation,
      boolean asTable,
      Model model,
      SettingsService settingsService,
      ReportEngine reportEngine,
      String tableViewName,
      String chartViewName) {
    return settingsService
        .baseCurrency()
        .map(
            baseCurrency -> {
              Rendered rendered = populate(presentation, baseCurrency, asTable, reportEngine);
              if (asTable) {
                model.addAttribute("report", rendered.report());
                model.addAttribute("hasChart", presentation.renderer() != Renderer.TABLE);
                return tableViewName;
              }
              model.addAttribute("chart", rendered.chart());
              return chartViewName;
            })
        .orElse(NO_BASE_CURRENCY_VIEW);
  }

  /**
   * A Frame's whole rendered state (reporting.md §11): {@code configured} false when {@code
   * selection} is {@link FrameSelection#EMPTY} or names no known Preset/Report (an emptied or stale
   * Frame — §16), {@code baseCurrencyUnset} true when it names a real one but the book has no base
   * currency yet. {@code report}/{@code chart} are set only when both are true, matching {@link
   * #populate}.
   */
  record FrameContent(
      boolean configured, boolean baseCurrencyUnset, ReportTableView report, ChartView chart) {}

  /**
   * Resolves {@code selection} to a Preset or a saved Report and renders it — the shared "what does
   * this Frame show" logic behind both {@link MainFrameController} (one Frame) and {@link
   * ReportsLayoutController} (N Frames), so an unconfigured Frame or a missing base currency is
   * handled identically everywhere.
   */
  static FrameContent renderFrame(
      FrameSelection selection,
      Optional<String> baseCurrency,
      ReportEngine reportEngine,
      ReportService reportService) {
    Optional<Presentation> presentation = resolve(selection, reportService);
    if (presentation.isEmpty()) {
      return new FrameContent(false, false, null, null);
    }
    if (baseCurrency.isEmpty()) {
      return new FrameContent(true, true, null, null);
    }
    Presentation shown = presentation.get();
    Rendered rendered =
        populate(shown, baseCurrency.get(), shown.renderer() == Renderer.TABLE, reportEngine);
    return new FrameContent(true, false, rendered.report(), rendered.chart());
  }

  private static Optional<Presentation> resolve(
      FrameSelection selection, ReportService reportService) {
    if (selection.reportId() != null) {
      return reportService.find(selection.reportId()).map(Presentation::of);
    }
    if (selection.presetSlug() != null) {
      return PresetCatalog.find(selection.presetSlug()).map(Presentation::of);
    }
    return Optional.empty();
  }
}
