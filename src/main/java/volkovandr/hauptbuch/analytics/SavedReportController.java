package volkovandr.hauptbuch.analytics;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * A saved Report's own page (reporting.md §14, plan stage d): {@code /reports/{id}}, the same
 * table/chart rendering and chart-table swap as a Preset's own page ({@link ReportController}),
 * plus the actions a Preset does not offer because it is code-defined and non-deletable — rename,
 * duplicate, delete.
 */
@Controller
class SavedReportController {

  private static final String BASE_PATH = "/reports";
  private static final String TABLE_VIEW = "table";
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
   * "preset" or "layout" would otherwise be an ambiguous match at request time).
   */
  @GetMapping(BASE_PATH + "/{reportId:\\d+}")
  String show(@PathVariable long reportId, Model model) {
    SavedReport saved = requireReport(reportId);
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", saved.name() + " · Hauptbuch");
    return render(saved, saved.renderer() == Renderer.TABLE, model, "report-table", "report-chart");
  }

  /** The chart/table swap fragment (reporting.md §10) — returns just the {@code #report-frame}. */
  @GetMapping(BASE_PATH + "/{reportId:\\d+}/view")
  String view(@PathVariable long reportId, @RequestParam String view, Model model) {
    SavedReport saved = requireReport(reportId);
    boolean asTable = saved.renderer() == Renderer.TABLE || TABLE_VIEW.equals(view);
    return render(saved, asTable, model, "report-table :: frame", "report-chart :: frame");
  }

  @PostMapping(BASE_PATH + "/{reportId:\\d+}/rename")
  String rename(@PathVariable long reportId, @RequestParam String name) {
    reportService.rename(reportId, name);
    return "redirect:" + BASE_PATH + "/" + reportId;
  }

  @PostMapping(BASE_PATH + "/{reportId:\\d+}/duplicate")
  String duplicate(@PathVariable long reportId, @RequestParam String name) {
    SavedReport copy = reportService.duplicate(reportId, name);
    return "redirect:" + BASE_PATH + "/" + copy.reportId();
  }

  @PostMapping(BASE_PATH + "/{reportId:\\d+}/delete")
  String delete(@PathVariable long reportId) {
    reportService.delete(reportId);
    return REDIRECT_TO_LIST;
  }

  private String render(
      SavedReport saved, boolean asTable, Model model, String tableViewName, String chartViewName) {
    model.addAttribute("savedReport", saved);
    model.addAttribute("viewBasePath", BASE_PATH + "/" + saved.reportId() + "/view");
    return PresetRendering.renderOwnPage(
        PresetRendering.Presentation.of(saved),
        asTable,
        model,
        settingsService,
        reportEngine,
        tableViewName,
        chartViewName);
  }

  private SavedReport requireReport(long reportId) {
    return reportService
        .find(reportId)
        .orElseThrow(() -> new IllegalArgumentException("No report with id " + reportId));
  }
}
