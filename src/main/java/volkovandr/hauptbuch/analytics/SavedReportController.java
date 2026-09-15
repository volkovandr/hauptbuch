package volkovandr.hauptbuch.analytics;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * A saved Report's own page and editor (reporting.md §14/§11a, plan stage d3): {@code
 * /reports/{id}}, the same rendering as a Preset's own page ({@link ReportController}) — including
 * the settings strip re-GETting this same URL on every change, an htmx request getting back just
 * the {@code #report-page} fragment — plus the actions a Preset does not offer because it is owned
 * and deletable: Save (overwrites in place, every Frame showing it follows), and Delete. "Save as
 * new report" is every editor page's own action, POSTed to {@link ReportEditorController} instead.
 */
@Controller
class SavedReportController {

  private static final String BASE_PATH = "/reports";
  private static final String REDIRECT_TO_LIST = "redirect:" + BASE_PATH;

  private final ReportService reportService;
  private final ReportEngine reportEngine;
  private final SettingsService settingsService;

  SavedReportController(
      ReportService reportService, ReportEngine reportEngine, SettingsService settingsService) {
    this.reportService = reportService;
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
  }

  /**
   * One saved Report, full page or (for an htmx settings-strip request) just its {@code
   * #report-page}. {@code reportId} is constrained to digits so this never contests {@code
   * /reports/preset/{slug}} (both are two-segment patterns with one literal and one variable
   * segment — a slug that happened to read "preset" or "new" would otherwise be an ambiguous match
   * at request time).
   */
  @GetMapping(BASE_PATH + "/{reportId:\\d+}")
  String show(
      @PathVariable long reportId,
      @RequestParam MultiValueMap<String, String> params,
      @RequestHeader(value = PresetRendering.HX_REQUEST_HEADER, required = false) String hxRequest,
      Model model) {
    SavedReport saved = requireReport(reportId);
    PresetRendering.Presentation effective =
        PresetRendering.resolvePresentation(PresetRendering.Presentation.of(saved), params);
    boolean unsaved = ReportSpecQueryString.isPresent(params);
    String pagePath = BASE_PATH + "/" + reportId;

    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", saved.name() + " · Hauptbuch");
    model.addAttribute(
        "editor",
        PresetRendering.editorView(
            effective, unsaved, reportId, saved.name(), pagePath, saved.name() + " copy"));
    return PresetRendering.renderOwnPage(
        effective,
        effective.renderer() == Renderer.TABLE,
        model,
        settingsService,
        reportEngine,
        pagePath,
        hxRequest);
  }

  /**
   * Save (reporting.md §11a.1): overwrites the Report's name, spec, renderer and trend line with
   * the actions strip's resubmitted current values — whatever was showing, draft or not.
   */
  @PostMapping(BASE_PATH + "/{reportId:\\d+}/save")
  String save(
      @PathVariable long reportId,
      @RequestParam String name,
      @RequestParam String renderer,
      @RequestParam boolean trendLine,
      @RequestParam MultiValueMap<String, String> params) {
    reportService.updateSpec(
        reportId,
        name,
        ReportSpecQueryString.fromParams(params),
        Renderer.valueOf(renderer),
        trendLine);
    return "redirect:" + BASE_PATH + "/" + reportId;
  }

  @PostMapping(BASE_PATH + "/{reportId:\\d+}/delete")
  String delete(@PathVariable long reportId) {
    reportService.delete(reportId);
    return REDIRECT_TO_LIST;
  }

  private SavedReport requireReport(long reportId) {
    return reportService
        .find(reportId)
        .orElseThrow(() -> new IllegalArgumentException("No report with id " + reportId));
  }
}
