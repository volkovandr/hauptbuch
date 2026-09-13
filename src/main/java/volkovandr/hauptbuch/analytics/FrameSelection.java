package volkovandr.hauptbuch.analytics;

/**
 * What a Frame's picker {@code <select>} is set to, encoded as one value so a single form field can
 * choose between a Preset (by slug) and a saved Report (by id) — reporting.md §11, plan stage d's
 * Frame/Report follow-up. {@link #decode} is the counterpart to the {@code "preset:"}/{@code
 * "report:"}-prefixed values the picker's own options carry (spelled out directly in the
 * templates); this is the one place the prefix scheme itself is pinned down.
 *
 * @param presetSlug set when the Frame shows a Preset; {@code null} otherwise
 * @param reportId set when the Frame shows a saved Report; {@code null} otherwise
 */
record FrameSelection(String presetSlug, Long reportId) {

  private static final String PRESET_PREFIX = "preset:";
  private static final String REPORT_PREFIX = "report:";

  static final FrameSelection EMPTY = new FrameSelection(null, null);

  /**
   * Decodes a submitted picker value. An unrecognised or blank value degrades to {@link #EMPTY}.
   */
  static FrameSelection decode(String raw) {
    if (raw == null || raw.isBlank()) {
      return EMPTY;
    }
    if (raw.startsWith(REPORT_PREFIX)) {
      try {
        return new FrameSelection(null, Long.parseLong(raw.substring(REPORT_PREFIX.length())));
      } catch (NumberFormatException malformed) {
        return EMPTY;
      }
    }
    if (raw.startsWith(PRESET_PREFIX)) {
      return new FrameSelection(raw.substring(PRESET_PREFIX.length()), null);
    }
    return EMPTY;
  }

  /** The encoded form the picker's current {@code <option value>} must match to render selected. */
  String encoded() {
    if (reportId != null) {
      return REPORT_PREFIX + reportId;
    }
    if (presetSlug != null) {
      return PRESET_PREFIX + presetSlug;
    }
    return "";
  }

  /**
   * The selection's own full page — {@code /reports/{id}} for a saved Report, {@code
   * /reports/preset/{slug}} for a Preset. The one place either URL scheme is spelled out, so {@link
   * MainFrameController} and {@link ReportsLayoutController} cannot drift on it. Callers only use
   * this once a Frame is known to be configured (see {@code PresetRendering.FrameContent}); {@code
   * null} for {@link #EMPTY}.
   */
  String fullReportUrl() {
    if (reportId != null) {
      return "/reports/" + reportId;
    }
    if (presetSlug != null) {
      return "/reports/preset/" + presetSlug;
    }
    return null;
  }
}
