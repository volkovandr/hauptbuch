package volkovandr.hauptbuch.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 */
@Controller
class ReportController {

  private static final String BASE_PATH = "/reports";
  private static final String NO_BASE_CURRENCY_VIEW = "report-unavailable";
  private static final String TABLE_VIEW = "table";

  private final ReportEngine reportEngine;
  private final SettingsService settingsService;

  ReportController(ReportEngine reportEngine, SettingsService settingsService) {
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
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

  private String render(
      PresetDef def,
      String slug,
      boolean asTable,
      Model model,
      String tableViewName,
      String chartViewName) {
    model.addAttribute("slug", slug);
    return settingsService
        .baseCurrency()
        .map(
            baseCurrency -> {
              PresetRendering.Rendered rendered =
                  PresetRendering.populate(def, baseCurrency, asTable, reportEngine);
              if (asTable) {
                model.addAttribute("report", rendered.report());
                model.addAttribute("hasChart", def.renderer() != Renderer.TABLE);
                return tableViewName;
              }
              model.addAttribute("chart", rendered.chart());
              return chartViewName;
            })
        .orElse(NO_BASE_CURRENCY_VIEW);
  }

  private static PresetDef presetFor(String slug) {
    return PresetCatalog.find(slug)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such preset: " + slug));
  }
}
