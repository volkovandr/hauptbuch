package volkovandr.hauptbuch.analytics;

/**
 * The report dimension catalogue (reporting.md §4): what a Report can group by, on rows, columns or
 * (from stage b) series. {@link #CATEGORY} and {@link #ACCOUNT} both walk the {@code account} tree
 * — they differ only in the {@link Scope#accountTypes()} a Report pairs them with — and {@link
 * #TAG} walks the {@code tag} tree; all three are hierarchical. {@link #PAYEE}, {@link #PERSON},
 * {@link #CURRENCY} and {@link #ACCOUNT_TYPE} are flat. {@link #DATE} is hierarchical from stage e
 * (the ladder); stage a buckets it at month granularity only.
 */
public enum Dimension {
  CATEGORY,
  ACCOUNT,
  TAG,
  PAYEE,
  PERSON,
  CURRENCY,
  ACCOUNT_TYPE,
  DATE
}
