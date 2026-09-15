package volkovandr.hauptbuch.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * A Preset's own page and editor (reporting.md §14/§16/§11a, plan stages b/d3): {@code
 * /reports/preset/{slug}}, code-defined and non-deletable. Query parameters encode an unsaved draft
 * (§11a.1, {@link ReportSpecQueryString}) that overrides the Preset's own spec, renderer and trend
 * line for this render only — a Preset can never be renamed, overwritten or deleted; {@code POST
 * /reports/save-as-new} ({@link ReportEditorController}) is the one bridge from a Preset to an
 * owned, editable Report. The settings strip (plan stage d3, {@link ReportSettingsView}) re-GETs
 * this same URL on every change; an htmx request ({@code HX-Request}) gets back just the {@code
 * #report-page} fragment, a plain browser request the full page.
 */
@Controller
class ReportController {

  private static final String BASE_PATH = "/reports";

  private final ReportEngine reportEngine;
  private final SettingsService settingsService;

  ReportController(ReportEngine reportEngine, SettingsService settingsService) {
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
  }

  /**
   * One Preset, full page or (for an htmx settings-strip request) just its {@code #report-page}.
   */
  @GetMapping(BASE_PATH + "/preset/{slug}")
  String preset(
      @PathVariable String slug,
      @RequestParam MultiValueMap<String, String> params,
      @RequestHeader(value = PresetRendering.HX_REQUEST_HEADER, required = false) String hxRequest,
      Model model) {
    PresetDef def = presetFor(slug);
    PresetRendering.Presentation effective =
        PresetRendering.resolvePresentation(PresetRendering.Presentation.of(def), params);
    boolean unsaved = ReportSpecQueryString.isPresent(params);
    String pagePath = BASE_PATH + "/preset/" + slug;

    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", def.title() + " · Hauptbuch");
    model.addAttribute(
        "editor",
        PresetRendering.editorView(
            effective, unsaved, null, null, pagePath, def.title() + " copy"));
    return PresetRendering.renderOwnPage(
        effective,
        effective.renderer() == Renderer.TABLE,
        model,
        settingsService,
        reportEngine,
        pagePath,
        hxRequest);
  }

  private static PresetDef presetFor(String slug) {
    return PresetCatalog.find(slug)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such preset: " + slug));
  }
}
