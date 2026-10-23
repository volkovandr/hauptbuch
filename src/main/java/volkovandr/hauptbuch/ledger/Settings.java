package volkovandr.hauptbuch.ledger;

/**
 * The single-row root settings entity (data-model §3.8).
 *
 * <p>Holds the write-once {@code baseCurrency} — required before any transaction is recorded (a
 * frozen {@code baseAmount} is denominated in it) and immutable thereafter — and the {@code
 * displayName} backing the "Hello, %name%" greeting. {@code baseCurrency} is {@code null} only on a
 * fresh book, until first-run setup sets it; the engine refuses to record a transaction while it is
 * null.
 *
 * @param baseCurrency ISO-4217 base currency; null until first-run set, then immutable
 * @param displayName freely-editable greeting name; nullable
 * @param confirmToastSeconds how long a receipt-confirmed toast stays visible; 0 = no toasts
 */
public record Settings(String baseCurrency, String displayName, int confirmToastSeconds) {

  /** The default lifetime of a receipt-confirmed toast, in seconds (also the column default). */
  public static final int DEFAULT_CONFIRM_TOAST_SECONDS = 3;

  /** The longest toast lifetime the settings screen accepts, in seconds. */
  public static final int MAX_CONFIRM_TOAST_SECONDS = 60;
}
