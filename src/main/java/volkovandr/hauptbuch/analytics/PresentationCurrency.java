package volkovandr.hauptbuch.analytics;

/**
 * Which currency a measure is valued in (reporting.md §5.4) — part of the measure itself, not a
 * report-wide setting, because a table may show a base column next to a native one.
 */
public enum PresentationCurrency {
  /** Valued in the book's base currency — the only presentation that can sum across currencies. */
  BASE,
  /**
   * Valued in each account's own currency, with no rate lookup at all. Legal only when every
   * account contributing to a cell shares one currency (§5.4); otherwise the cell renders {@code
   * —}.
   */
  ACCOUNT
}
