package volkovandr.hauptbuch.analytics;

import org.springframework.ui.Model;

/**
 * Renders a Preset's spec as its table or chart view (reporting.md §10/§16) — the shared middle of
 * {@link ReportController} (a Preset's own page, with its chart/table swap) and {@link
 * MainFrameController} (a Preset shown in the main page's Frame, with no swap), so the two callers
 * cannot silently drift on how a Preset renders.
 */
final class PresetRendering {

  private PresetRendering() {}

  /**
   * Populates {@code model} with {@code report} (a table) or {@code chart}, per {@code asTable}.
   */
  static void populate(
      PresetDef def, String baseCurrency, boolean asTable, ReportEngine reportEngine, Model model) {
    ReportGrid grid = reportEngine.render(def.spec());
    if (asTable) {
      model.addAttribute(
          "report", ReportTableViewAssembler.assemble(def.title(), def.spec(), grid, baseCurrency));
    } else {
      model.addAttribute(
          "chart",
          ChartViewAssembler.assemble(
              def.title(), def.spec(), grid, baseCurrency, def.renderer(), def.trendLine()));
    }
  }
}
