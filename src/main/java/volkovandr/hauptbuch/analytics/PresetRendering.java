package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.ui.Model;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Renders a Preset's spec as its table or chart view (reporting.md §10/§16) — the shared middle of
 * {@link ReportController} (a Preset's own page), {@link SavedReportController} (a saved Report's
 * own page, plan stage d), {@link ReportEditorController} (a not-yet-saved {@code /reports/new}
 * draft, plan stage d3), {@link MainFrameController} (a Preset shown in the main page's Frame), and
 * {@link ReportsLayoutController} (a Preset shown in one of the reporting page's Frames), so none
 * of the five can silently drift on how a Report renders. {@link #resolvePresentation} and {@link
 * #editorView} are the editor pages' (§11a.1's unsaved-draft pieces) shared logic in the same
 * spirit; the settings strip's own view is {@link ReportSettingsView}, built inside {@link
 * #renderOwnPage}.
 */
final class PresetRendering {

  private static final String NO_BASE_CURRENCY_VIEW = "report-unavailable";

  /**
   * The header name every editor page's {@code @RequestHeader} binds to decide between its full
   * page and just the {@code #report-page} fragment — one shared constant so the three controllers
   * ({@link ReportController}, {@link SavedReportController}, {@link ReportEditorController})
   * cannot drift on the header they read, the same one {@link
   * volkovandr.hauptbuch.web.GlobalHtmxErrorAdvice} checks for its own, unrelated purpose.
   */
  static final String HX_REQUEST_HEADER = "HX-Request";

  private PresetRendering() {}

  /** Whether the {@code HX-Request} header names an htmx request. */
  private static boolean isHtmxRequest(String hxRequestHeader) {
    return "true".equalsIgnoreCase(hxRequestHeader);
  }

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

  /**
   * The editor actions strip's own view (reporting.md §11a.1/§11a.2): Save (a saved Report only,
   * {@code reportId != null}), Save as new report (every page), Discard changes and the Unsaved
   * marker (both only while {@link #unsaved} holds), and Delete (a saved Report only).
   *
   * @param reportId {@code null} for a Preset or a not-yet-saved {@code /reports/new} draft
   * @param currentName the saved Report's own name, editable alongside Save; {@code null} otherwise
   * @param discardUrl the page's own bare URL — no spec parameters, so it renders {@code base}
   * @param saveAsNewName the "Save as new report" field's prefilled value
   * @param specParams the effective spec, already encoded ({@link ReportSpecQueryString}) as the
   *     hidden fields both the Save and Save-as-new forms resubmit
   */
  record ReportEditorView(
      Long reportId,
      String currentName,
      boolean unsaved,
      String discardUrl,
      String saveAsNewName,
      MultiValueMap<String, String> specParams,
      String renderer,
      boolean trendLine) {}

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
   * ReportController} and {@link SavedReportController} both need, including the settings strip's
   * own view ({@link ReportSettingsView}, plan stage d3). Pulled out of the controllers themselves
   * (plan stage d) so the base-currency branch and the model wiring live in exactly one place,
   * matching this class's own reason for existing. {@code hxRequestHeader} decides, in this one
   * place, whether the caller gets back {@code report-table}/{@code report-chart}'s full page or
   * just their {@code :: page} fragment — every editor page made this choice the same way, so
   * settling it here keeps a fourth caller from having to redecide it.
   */
  static String renderOwnPage(
      Presentation presentation,
      boolean asTable,
      Model model,
      SettingsService settingsService,
      ReportEngine reportEngine,
      String pagePath,
      String hxRequestHeader) {
    boolean fragment = isHtmxRequest(hxRequestHeader);
    String tableViewName = fragment ? "report-table :: page" : "report-table";
    String chartViewName = fragment ? "report-chart :: page" : "report-chart";
    return settingsService
        .baseCurrency()
        .map(
            baseCurrency -> {
              Rendered rendered = populate(presentation, baseCurrency, asTable, reportEngine);
              model.addAttribute(
                  "settings", ReportSettingsView.build(presentation, pagePath, LocalDate.now()));
              if (asTable) {
                model.addAttribute("report", rendered.report());
                return tableViewName;
              }
              model.addAttribute("chart", rendered.chart());
              return chartViewName;
            })
        .orElse(NO_BASE_CURRENCY_VIEW);
  }

  /**
   * The spec a Report's page actually renders (reporting.md §11a.1): {@code params}' own draft when
   * {@link ReportSpecQueryString#isPresent} says one is there, else {@code base}'s own saved/Preset
   * spec unchanged. {@code base}'s title never comes from the query string — the editor offers no
   * control for it. The renderer and trend line (plan stage d3's own settings-strip controls) are
   * read independently, defaulting to {@code base}'s own when the draft's request happened not to
   * carry them — it always does in practice, since every settings-strip form resubmits {@link
   * #allParams} whole, but a hand-typed spec-only URL should still resolve sensibly.
   */
  static Presentation resolvePresentation(Presentation base, MultiValueMap<String, String> params) {
    if (!ReportSpecQueryString.isPresent(params)) {
      return base;
    }
    String rendererParam = params.getFirst("renderer");
    String trendLineParam = params.getFirst("trendLine");
    Renderer renderer = rendererParam == null ? base.renderer() : Renderer.valueOf(rendererParam);
    boolean trendLine =
        trendLineParam == null ? base.trendLine() : Boolean.parseBoolean(trendLineParam);
    return new Presentation(
        base.title(), ReportSpecQueryString.fromParams(params), renderer, trendLine);
  }

  /**
   * {@code effective}'s whole editable state as query parameters (plan stage d3): {@link
   * ReportSpecQueryString#toParams}'s spec fields plus {@code renderer}/{@code trendLine}, which
   * are "promoted columns" (§14) rather than part of {@link ReportSpec} itself. The settings
   * strip's own groups ({@link ReportSettingsView}) each resubmit this whole map, minus the
   * field(s) the group itself owns, as hidden fields alongside its own real inputs.
   */
  static MultiValueMap<String, String> allParams(Presentation effective) {
    MultiValueMap<String, String> params =
        new LinkedMultiValueMap<>(ReportSpecQueryString.toParams(effective.spec()));
    params.add("renderer", effective.renderer().name());
    params.add("trendLine", String.valueOf(effective.trendLine()));
    return params;
  }

  /**
   * Builds the editor actions strip's view from an already-{@link #resolvePresentation}d {@code
   * effective} — see {@link ReportEditorView}'s own javadoc for what each field means.
   *
   * @param pagePath the page's own bare URL, reused as {@link ReportEditorView#discardUrl}
   */
  static ReportEditorView editorView(
      Presentation effective,
      boolean unsaved,
      Long reportId,
      String currentName,
      String pagePath,
      String saveAsNewName) {
    return new ReportEditorView(
        reportId,
        currentName,
        unsaved,
        pagePath,
        saveAsNewName,
        ReportSpecQueryString.toParams(effective.spec()),
        effective.renderer().name(),
        effective.trendLine());
  }

  /**
   * A Frame's whole rendered state (reporting.md §11): {@code configured} false when {@code
   * selection} is {@link FrameSelection#EMPTY} or names no known Preset/Report (an emptied or stale
   * Frame — §16), {@code baseCurrencyUnset} true when it names a real one but the book has no base
   * currency yet. {@code title} is the resolved Preset/Report's name, {@code null} when
   * unconfigured (the Frame fragment's heading, plan stage d2). {@code report}/{@code chart} are
   * set only when both flags allow it, matching {@link #populate}.
   */
  record FrameContent(
      boolean configured,
      boolean baseCurrencyUnset,
      String title,
      ReportTableView report,
      ChartView chart) {}

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
      return new FrameContent(false, false, null, null, null);
    }
    Presentation shown = presentation.get();
    if (baseCurrency.isEmpty()) {
      return new FrameContent(true, true, shown.title(), null, null);
    }
    Rendered rendered =
        populate(shown, baseCurrency.get(), shown.renderer() == Renderer.TABLE, reportEngine);
    return new FrameContent(true, false, shown.title(), rendered.report(), rendered.chart());
  }

  /**
   * Whether {@code selection} names a known Preset or saved Report — the same check {@link
   * #renderFrame} makes, exposed for a caller (the main page's picker, plan stage d2) that only
   * needs the placeholder-option decision, not a full render.
   */
  static boolean isKnown(FrameSelection selection, ReportService reportService) {
    return resolve(selection, reportService).isPresent();
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
