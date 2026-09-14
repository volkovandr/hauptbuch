package volkovandr.hauptbuch.analytics;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * A saved Report's own page and editor (reporting.md §14/§11a, plan stage d3): {@code
 * /reports/{id}}, the same table/chart rendering and chart-table swap as a Preset's own page
 * ({@link ReportController}), plus the actions a Preset does not offer because it is owned and
 * deletable — Save (overwrites in place, every Frame showing it follows), and Delete. "Save as new
 * report" is every editor page's own action, POSTed to {@link ReportEditorController} instead.
 */
@Controller
class SavedReportController {

  private static final String BASE_PATH = "/reports";
  private static final String TABLE_VIEW = "table";
  private static final String CHART_VIEW = "chart";
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
   * One saved Report, full page — its own renderer, or the table if it has none. {@code reportId}
   * is constrained to digits so this never contests {@code /reports/preset/{slug}} (both are
   * two-segment patterns with one literal and one variable segment — a slug that happened to read
   * "preset" or "new" would otherwise be an ambiguous match at request time).
   */
  @GetMapping(BASE_PATH + "/{reportId:\\d+}")
  String show(
      @PathVariable long reportId,
      @RequestParam MultiValueMap<String, String> params,
      Model model) {
    SavedReport saved = requireReport(reportId);
    PresetRendering.Presentation effective =
        PresetRendering.resolvePresentation(PresetRendering.Presentation.of(saved), params);
    boolean unsaved = ReportSpecQueryString.isPresent(params);
    String pagePath = BASE_PATH + "/" + reportId;
    boolean asTable = effective.renderer() == Renderer.TABLE;

    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", saved.name() + " · Hauptbuch");
    model.addAttribute(
        "editor",
        PresetRendering.editorView(
            effective, unsaved, reportId, saved.name(), pagePath, saved.name() + " copy"));
    model.addAttribute(
        "viewToggleUrl",
        PresetRendering.viewToggleUrl(
            pagePath + "/view", asTable ? CHART_VIEW : TABLE_VIEW, unsaved, effective.spec()));
    model.addAttribute(
        "canonicalUrl", PresetRendering.canonicalUrl(pagePath, unsaved, effective.spec()));
    return PresetRendering.renderOwnPage(
        effective, asTable, model, settingsService, reportEngine, "report-table", "report-chart");
  }

  /** The chart/table swap fragment (reporting.md §10) — returns just the {@code #report-frame}. */
  @GetMapping(BASE_PATH + "/{reportId:\\d+}/view")
  String view(
      @PathVariable long reportId,
      @RequestParam String view,
      @RequestParam MultiValueMap<String, String> params,
      Model model) {
    SavedReport saved = requireReport(reportId);
    PresetRendering.Presentation effective =
        PresetRendering.resolvePresentation(PresetRendering.Presentation.of(saved), params);
    boolean unsaved = ReportSpecQueryString.isPresent(params);
    boolean asTable = saved.renderer() == Renderer.TABLE || TABLE_VIEW.equals(view);
    String pagePath = BASE_PATH + "/" + reportId;

    model.addAttribute(
        "viewToggleUrl",
        PresetRendering.viewToggleUrl(
            pagePath + "/view", asTable ? CHART_VIEW : TABLE_VIEW, unsaved, effective.spec()));
    model.addAttribute(
        "canonicalUrl", PresetRendering.canonicalUrl(pagePath, unsaved, effective.spec()));
    return PresetRendering.renderOwnPage(
        effective,
        asTable,
        model,
        settingsService,
        reportEngine,
        "report-table :: frame",
        "report-chart :: frame");
  }

  /**
   * Save (reporting.md §11a.1): overwrites the Report's name and spec with the actions strip's
   * resubmitted current values — whatever was showing, draft or not.
   */
  @PostMapping(BASE_PATH + "/{reportId:\\d+}/save")
  String save(
      @PathVariable long reportId,
      @RequestParam String name,
      @RequestParam MultiValueMap<String, String> params) {
    reportService.updateSpec(reportId, name, ReportSpecQueryString.fromParams(params));
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
