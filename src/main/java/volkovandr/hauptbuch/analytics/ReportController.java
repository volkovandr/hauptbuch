package volkovandr.hauptbuch.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The four Presets' own renderer and editor (reporting.md §14/§16/§11a, plan stages b/d3): {@code
 * /reports/preset/{slug}}, code-defined and non-deletable. Query parameters encode an unsaved draft
 * (§11a.1, {@link ReportSpecQueryString}) that overrides the Preset's own spec for this render only
 * — a Preset can never be renamed, overwritten or deleted; {@code POST /reports/save-as-new}
 * ({@link ReportEditorController}) is the one bridge from a Preset to an owned, editable Report. A
 * chart Preset can swap to its table and back in place ({@code /reports/preset/{slug}/view}), the
 * same hx-get/hx-swap idiom the receipt image toggle uses.
 */
@Controller
class ReportController {

  private static final String BASE_PATH = "/reports";
  private static final String TABLE_VIEW = "table";
  private static final String CHART_VIEW = "chart";

  private final ReportEngine reportEngine;
  private final SettingsService settingsService;

  ReportController(ReportEngine reportEngine, SettingsService settingsService) {
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
  }

  /** One Preset, full page — the chosen renderer, or the table if it has none. */
  @GetMapping(BASE_PATH + "/preset/{slug}")
  String preset(
      @PathVariable String slug, @RequestParam MultiValueMap<String, String> params, Model model) {
    PresetDef def = presetFor(slug);
    PresetRendering.Presentation effective =
        PresetRendering.resolvePresentation(PresetRendering.Presentation.of(def), params);
    boolean unsaved = ReportSpecQueryString.isPresent(params);
    String pagePath = BASE_PATH + "/preset/" + slug;
    boolean asTable = effective.renderer() == Renderer.TABLE;

    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", def.title() + " · Hauptbuch");
    model.addAttribute(
        "editor",
        PresetRendering.editorView(
            effective, unsaved, null, null, pagePath, def.title() + " copy"));
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
  @GetMapping(BASE_PATH + "/preset/{slug}/view")
  String presetView(
      @PathVariable String slug,
      @RequestParam String view,
      @RequestParam MultiValueMap<String, String> params,
      Model model) {
    PresetDef def = presetFor(slug);
    PresetRendering.Presentation effective =
        PresetRendering.resolvePresentation(PresetRendering.Presentation.of(def), params);
    boolean unsaved = ReportSpecQueryString.isPresent(params);
    // A TABLE-only Preset has nothing to swap to — clamp rather than ask ChartViewAssembler to
    // build a chart panel for a renderer that has none (a hand-typed/stale ?view=chart URL).
    boolean asTable = def.renderer() == Renderer.TABLE || TABLE_VIEW.equals(view);
    String pagePath = BASE_PATH + "/preset/" + slug;

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

  private static PresetDef presetFor(String slug) {
    return PresetCatalog.find(slug)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such preset: " + slug));
  }
}
