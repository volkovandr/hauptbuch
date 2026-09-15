package volkovandr.hauptbuch.analytics;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * {@code /reports/new} (reporting.md §11a.1, plan stage d3): a not-yet-saved Report's own page,
 * starting from the category × month matrix spec — a spec always needs at least one measure ({@link
 * ReportSpec}'s own constructor), so it cannot start empty. Its settings strip re-GETs this same
 * URL on every change, an htmx request getting back just the {@code #report-page} fragment, like
 * the other two editor pages ({@link ReportController}, {@link SavedReportController}). {@code POST
 * /reports/save-as-new} is the "Save as new report" action every editor page offers — a Preset, a
 * saved Report with unsaved changes, or this page all post here, rather than each carrying its own
 * copy of "mint a Report from the current draft".
 */
@Controller
class ReportEditorController {

  private static final String BASE_PATH = "/reports";
  private static final String NEW_PATH = BASE_PATH + "/new";

  private final ReportEngine reportEngine;
  private final SettingsService settingsService;
  private final ReportService reportService;

  ReportEditorController(
      ReportEngine reportEngine, SettingsService settingsService, ReportService reportService) {
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
    this.reportService = reportService;
  }

  @GetMapping(NEW_PATH)
  String newReport(
      @RequestParam MultiValueMap<String, String> params,
      @RequestHeader(value = PresetRendering.HX_REQUEST_HEADER, required = false) String hxRequest,
      Model model) {
    PresetRendering.Presentation base =
        new PresetRendering.Presentation(
            "New report", Presets.categoryMonthMatrix(), Renderer.TABLE, false);
    PresetRendering.Presentation effective = PresetRendering.resolvePresentation(base, params);
    boolean unsaved = ReportSpecQueryString.isPresent(params);

    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "New report · Hauptbuch");
    model.addAttribute(
        "editor", PresetRendering.editorView(effective, unsaved, null, null, NEW_PATH, ""));
    return PresetRendering.renderOwnPage(
        effective,
        effective.renderer() == Renderer.TABLE,
        model,
        settingsService,
        reportEngine,
        NEW_PATH,
        hxRequest);
  }

  /**
   * "Save as new report" (reporting.md §11a.1): mints a new owned Report from whatever the caller's
   * actions strip resubmits — a Preset's own spec, a saved Report's (with or without unsaved
   * changes), or a not-yet-saved {@code /reports/new} draft; this endpoint does not need to know
   * which.
   */
  @PostMapping(BASE_PATH + "/save-as-new")
  String saveAsNew(
      @RequestParam String name,
      @RequestParam String renderer,
      @RequestParam boolean trendLine,
      @RequestParam MultiValueMap<String, String> params) {
    ReportSpec spec = ReportSpecQueryString.fromParams(params);
    SavedReport saved = reportService.save(name, spec, Renderer.valueOf(renderer), trendLine);
    return "redirect:" + BASE_PATH + "/" + saved.reportId();
  }
}
