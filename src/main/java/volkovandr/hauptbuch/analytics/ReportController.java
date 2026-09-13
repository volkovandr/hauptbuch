package volkovandr.hauptbuch.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The four Presets' own renderers (reporting.md §14/§16, plan stage b): {@code
 * /reports/preset/{slug}}, code-defined and non-deletable. A chart Preset can swap to its table and
 * back in place ({@code /reports/preset/{slug}/view}), the same hx-get/hx-swap idiom the receipt
 * image toggle uses. The reporting page itself ({@code /reports}) is {@link
 * ReportsLayoutController}'s job — its own Layout reaches the same Presets via a Frame's picker.
 *
 * <p>{@code /reports/preset/{slug}/copy} (plan stage d) is the one bridge from a Preset to a saved,
 * editable Report: {@link ReportService#copyFromPreset} clones its spec into an owned row, leaving
 * the Preset itself untouched and still non-deletable ({@link SavedReportController} owns a saved
 * Report's own page and its rename/duplicate/delete actions). It resolves the slug via {@link
 * #presetFor}, the same 404-on-unknown-slug lookup every other Preset route here uses, rather than
 * asking {@link ReportService} to know about the code-defined catalog.
 */
@Controller
class ReportController {

  private static final String BASE_PATH = "/reports";
  private static final String TABLE_VIEW = "table";

  private final ReportEngine reportEngine;
  private final SettingsService settingsService;
  private final ReportService reportService;

  ReportController(
      ReportEngine reportEngine, SettingsService settingsService, ReportService reportService) {
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
    this.reportService = reportService;
  }

  /** One Preset, full page — the chosen renderer, or the table if it has none. */
  @GetMapping(BASE_PATH + "/preset/{slug}")
  String preset(@PathVariable String slug, Model model) {
    PresetDef def = presetFor(slug);
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", def.title() + " · Hauptbuch");
    return render(
        def, slug, def.renderer() == Renderer.TABLE, model, "report-table", "report-chart");
  }

  /** The chart/table swap fragment (reporting.md §10) — returns just the {@code #report-frame}. */
  @GetMapping(BASE_PATH + "/preset/{slug}/view")
  String presetView(@PathVariable String slug, @RequestParam String view, Model model) {
    PresetDef def = presetFor(slug);
    // A TABLE-only Preset has nothing to swap to — clamp rather than ask ChartViewAssembler to
    // build a chart panel for a renderer that has none (a hand-typed/stale ?view=chart URL).
    boolean asTable = def.renderer() == Renderer.TABLE || TABLE_VIEW.equals(view);
    return render(def, slug, asTable, model, "report-table :: frame", "report-chart :: frame");
  }

  /** "Copy to my reports" (plan stage d): clones the Preset's spec into a new owned Report. */
  @PostMapping(BASE_PATH + "/preset/{slug}/copy")
  String copyToOwnReports(@PathVariable String slug, @RequestParam String name) {
    SavedReport copy = reportService.copyFromPreset(presetFor(slug), name);
    return "redirect:" + BASE_PATH + "/" + copy.reportId();
  }

  private String render(
      PresetDef def,
      String slug,
      boolean asTable,
      Model model,
      String tableViewName,
      String chartViewName) {
    model.addAttribute("slug", slug);
    model.addAttribute("viewBasePath", BASE_PATH + "/preset/" + slug + "/view");
    return PresetRendering.renderOwnPage(
        PresetRendering.Presentation.of(def),
        asTable,
        model,
        settingsService,
        reportEngine,
        tableViewName,
        chartViewName);
  }

  private static PresetDef presetFor(String slug) {
    return PresetCatalog.find(slug)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such preset: " + slug));
  }
}
