package volkovandr.hauptbuch.analytics;

import java.util.List;
import java.util.Optional;

/**
 * The four Presets (reporting.md §16), keyed by their URL slug — shared by {@link ReportController}
 * (a Preset's own page, {@code /reports/preset/*}) and {@link MainFrameController} (a Preset shown
 * in the main page's Frame, reporting.md §11), so the two never drift apart on what a slug means.
 */
final class PresetCatalog {

  private static final List<PresetDef> PRESETS =
      List.of(
          new PresetDef(
              Presets.CATEGORY_MONTH_MATRIX_SLUG,
              Presets.categoryMonthMatrix(),
              "Category × month matrix",
              Renderer.TABLE,
              false),
          new PresetDef(
              Presets.BALANCE_SHEET_SLUG,
              Presets.balanceSheet(),
              "Balance sheet",
              Renderer.TABLE,
              false),
          new PresetDef(
              Presets.NET_WORTH_OVER_TIME_SLUG,
              Presets.netWorthOverTime(),
              "Net worth over time",
              Renderer.LINE,
              true),
          new PresetDef(
              Presets.THIS_MONTH_VS_LAST_SLUG,
              Presets.thisMonthVsLast(),
              "This month vs last",
              Renderer.BAR,
              false));

  private PresetCatalog() {}

  /** Every Preset, in display order — a Frame's picker (reporting.md §11) lists all of them. */
  static List<PresetDef> all() {
    return PRESETS;
  }

  /** The Preset for {@code slug}, or empty when the slug names no known Preset. */
  static Optional<PresetDef> find(String slug) {
    return PRESETS.stream().filter(def -> def.slug().equals(slug)).findFirst();
  }
}
