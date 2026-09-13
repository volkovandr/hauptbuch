package volkovandr.hauptbuch.analytics;

import java.util.Optional;

/**
 * Renders a Preset's spec as its table or chart view (reporting.md §10/§16) — the shared middle of
 * {@link ReportController} (a Preset's own page, with its chart/table swap), {@link
 * MainFrameController} (a Preset shown in the main page's Frame, with no swap), and {@link
 * ReportsLayoutController} (a Preset shown in one of the reporting page's Frames), so none of the
 * three can silently drift on how a Preset renders.
 */
final class PresetRendering {

  private PresetRendering() {}

  /** Either {@link #report} (a table) or {@link #chart}, per the caller's {@code asTable}. */
  record Rendered(ReportTableView report, ChartView chart) {}

  /**
   * A Frame's whole rendered state (reporting.md §11), from a raw Preset slug: {@code configured}
   * false when the slug is {@code null} or names no known Preset (an emptied or stale Frame — §16),
   * {@code baseCurrencyUnset} true when it names a real Preset but the book has no base currency
   * yet. {@code report}/{@code chart} are set only when both are true, matching {@link #populate}.
   */
  record FrameContent(
      boolean configured, boolean baseCurrencyUnset, ReportTableView report, ChartView chart) {}

  /** Renders {@code def} as its table or chart view, per {@code asTable}. */
  static Rendered populate(
      PresetDef def, String baseCurrency, boolean asTable, ReportEngine reportEngine) {
    ReportGrid grid = reportEngine.render(def.spec());
    if (asTable) {
      return new Rendered(
          ReportTableViewAssembler.assemble(def.title(), def.spec(), grid, baseCurrency), null);
    }
    return new Rendered(
        null,
        ChartViewAssembler.assemble(
            def.title(), def.spec(), grid, baseCurrency, def.renderer(), def.trendLine()));
  }

  /**
   * Resolves {@code slug} to a Preset and renders it — the shared "what does this Frame show" logic
   * behind both {@link MainFrameController} (one Frame) and {@link ReportsLayoutController} (N
   * Frames), so an unconfigured Frame or a missing base currency is handled identically everywhere.
   */
  static FrameContent renderFrame(
      String slug, Optional<String> baseCurrency, ReportEngine reportEngine) {
    Optional<PresetDef> def = slug == null ? Optional.empty() : PresetCatalog.find(slug);
    if (def.isEmpty()) {
      return new FrameContent(false, false, null, null);
    }
    if (baseCurrency.isEmpty()) {
      return new FrameContent(true, true, null, null);
    }
    PresetDef preset = def.get();
    Rendered rendered =
        populate(preset, baseCurrency.get(), preset.renderer() == Renderer.TABLE, reportEngine);
    return new FrameContent(true, false, rendered.report(), rendered.chart());
  }
}
