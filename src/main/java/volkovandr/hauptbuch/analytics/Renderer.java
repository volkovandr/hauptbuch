package volkovandr.hauptbuch.analytics;

/**
 * How a Report's grid is drawn (reporting.md §10). Not part of {@link ReportSpec} — it is a
 * "promoted column" alongside the spec (§14), the same way a saved Report's name will be; a Preset
 * pairs one with its spec in code (stage b ships {@link #LINE} and {@link #BAR} alongside the
 * table).
 */
public enum Renderer {
  TABLE,
  LINE,
  BAR,
  PIE
}
