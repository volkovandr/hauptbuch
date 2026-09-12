package volkovandr.hauptbuch.analytics;

/**
 * One column of a Report (reporting.md §5.4): a {@link MeasureKind} plus, for the two money kinds,
 * the {@link PresentationCurrency} it is valued in and — for {@link MeasureKind#TURNOVER} only —
 * the {@link Leg} it counts. The count kinds ({@link MeasureKind#COUNT_POSTINGS}, {@link
 * MeasureKind#COUNT_TRANSACTIONS}) need neither (§5.5).
 *
 * @param kind what is being measured
 * @param currency required for {@code TURNOVER}/{@code CLOSING_BALANCE}; {@code null} for a count
 * @param leg required for {@code TURNOVER}; {@code null} otherwise
 */
public record Measure(MeasureKind kind, PresentationCurrency currency, Leg leg) {

  /** Enforces which of {@code currency}/{@code leg} each {@link MeasureKind} needs or forbids. */
  public Measure {
    boolean isTurnover = kind == MeasureKind.TURNOVER;
    if (isTurnover && (currency == null || leg == null)) {
      throw new IllegalArgumentException("A turnover measure needs a currency and a leg.");
    }
    boolean isClosingBalance = kind == MeasureKind.CLOSING_BALANCE;
    if (isClosingBalance && currency == null) {
      throw new IllegalArgumentException("A closing-balance measure needs a currency.");
    }
    if (isClosingBalance && leg != null) {
      throw new IllegalArgumentException("A closing-balance measure has no leg.");
    }
    boolean isCount = kind == MeasureKind.COUNT_POSTINGS || kind == MeasureKind.COUNT_TRANSACTIONS;
    if (isCount && (currency != null || leg != null)) {
      throw new IllegalArgumentException("A count measure has no currency and no leg.");
    }
  }

  /** A turnover measure valued in {@code currency}, counting {@code leg} (§5.1, §5.3). */
  public static Measure turnover(PresentationCurrency currency, Leg leg) {
    return new Measure(MeasureKind.TURNOVER, currency, leg);
  }

  /** A closing-balance measure valued in {@code currency} (§5.1). */
  public static Measure closingBalance(PresentationCurrency currency) {
    return new Measure(MeasureKind.CLOSING_BALANCE, currency, null);
  }

  /** The count of postings in scope (§5.5). */
  public static Measure countPostings() {
    return new Measure(MeasureKind.COUNT_POSTINGS, null, null);
  }

  /** The count of distinct transactions in scope (§5.5). */
  public static Measure countTransactions() {
    return new Measure(MeasureKind.COUNT_TRANSACTIONS, null, null);
  }
}
