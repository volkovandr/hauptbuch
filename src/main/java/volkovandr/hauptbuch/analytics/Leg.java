package volkovandr.hauptbuch.analytics;

/**
 * Which side of the accounts in scope a {@link MeasureKind#TURNOVER} measure counts (reporting.md
 * §5.3) — independent of account type, because the stored sign convention (data-model §4) never
 * flips by type. Spending is a {@link #CREDITS} entry on a debit card (asset) and a credit card
 * (liability) alike.
 */
public enum Leg {
  DEBITS,
  CREDITS,
  NET
}
