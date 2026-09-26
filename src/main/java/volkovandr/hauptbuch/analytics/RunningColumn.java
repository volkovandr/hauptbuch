package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import volkovandr.hauptbuch.analytics.repository.PostingValue;

/**
 * A drill-down list's running column (reporting.md §12): the figure's measure accumulated row by
 * row, following the figure's own arithmetic so the last row lands on it — each posting flipped as
 * its cell is (data-model §4.1), a base figure turning {@code —} at the first posting with no rate,
 * a native one at the second currency, and a transaction counted once per cell and currency (the
 * way {@link CellValuation} sums its per-currency groups). A closing balance's column starts from
 * its opening-balance line instead of zero, and values everything at the period-end rate ({@link
 * #closingBalance}).
 */
final class RunningColumn {

  private RunningColumn() {}

  /**
   * One listed posting, and the cell of the figure it counts in.
   *
   * @param posting the posting and its value
   * @param creditNatural whether its cell displays sign-flipped
   * @param addend which of the figure's cells it sits in — a body cell has one; a total has many
   */
  record Entry(PostingValue posting, boolean creditNatural, int addend) {}

  /**
   * {@code values}, in their order, as entries — a posting in two of a total's cells listed once
   * per cell, as the total counts it.
   */
  static List<Entry> entries(
      List<PostingValue> values, Map<Long, List<DrillSource.Membership>> membershipsByPosting) {
    return values.stream()
        .flatMap(
            value ->
                membershipsByPosting.get(value.postingId()).stream()
                    .map(m -> new Entry(value, m.creditNatural(), m.addend())))
        .toList();
  }

  /** The running figure after each of {@code entries}, in their order. */
  static List<Cell> of(Measure measure, String baseCurrency, List<Entry> entries) {
    return switch (measure.kind()) {
      case COUNT_POSTINGS -> countPostings(entries);
      case COUNT_TRANSACTIONS -> countTransactions(entries);
      case TURNOVER ->
          measure.currency() == PresentationCurrency.BASE
              ? baseTurnover(baseCurrency, entries)
              : nativeTurnover(entries);
      case CLOSING_BALANCE ->
          throw new IllegalArgumentException("A closing balance runs from its opening line.");
    };
  }

  /**
   * One balance group's part of a closing-balance list's opening line: what the group held before
   * the cell's period.
   *
   * @param currencyCode the group's currency
   * @param nativeAmount its balance the day before the period — its closing balance less the
   *     postings listed
   * @param periodPostings whether any of its postings are listed below the line
   * @param creditNatural whether its cell displays sign-flipped
   */
  record OpeningPart(
      String currencyCode, BigDecimal nativeAmount, boolean periodPostings, boolean creditNatural) {

    /**
     * Whether this group shows in the opening line at all: it held something, or it has no listed
     * posting to show up at — so its currency counts there, exactly as its cell counts it.
     */
    boolean showsInOpening() {
      return !periodPostings || nativeAmount.signum() != 0;
    }
  }

  /**
   * A closing-balance list's opening line and its running figure after each row (reporting.md §12).
   * In base, the opening line and every row are valued at the cell's period-end rate — the
   * mark-to-market rule the cell itself follows (§5.4) — so the column closes on the cell exactly;
   * it turns {@code —} where a currency with no rate first shows. In account currency it turns
   * {@code —} where a second currency first shows.
   *
   * @param openings each balance group's part of the opening line
   * @param entries the listed postings, in order
   * @param ratesAtPeriodEnd each non-base currency's rate at the period end; a currency missing
   *     from it has none
   */
  static BalanceRun closingBalance(
      Measure measure,
      String baseCurrency,
      List<OpeningPart> openings,
      List<Entry> entries,
      Map<String, BigDecimal> ratesAtPeriodEnd) {
    return measure.currency() == PresentationCurrency.BASE
        ? baseClosingBalance(baseCurrency, openings, entries, ratesAtPeriodEnd)
        : nativeClosingBalance(openings, entries);
  }

  /**
   * A closing-balance list's figures.
   *
   * @param opening the opening line's figure
   * @param running the running figure after each listed posting
   */
  record BalanceRun(Cell opening, List<Cell> running) {

    /** Defensively copies the running figures. */
    BalanceRun {
      running = List.copyOf(running);
    }
  }

  private static BalanceRun baseClosingBalance(
      String baseCurrency,
      List<OpeningPart> openings,
      List<Entry> entries,
      Map<String, BigDecimal> rates) {
    Cell missingRate = new Cell.Illegal(Cell.Reason.MISSING_RATE);
    BigDecimal sum = BigDecimal.ZERO;
    boolean missing = false;
    for (OpeningPart opening : openings) {
      BigDecimal value =
          atRate(opening.nativeAmount(), opening.currencyCode(), baseCurrency, rates);
      if (value == null) {
        missing = missing || opening.showsInOpening();
        continue;
      }
      sum = sum.add(opening.creditNatural() ? value.negate() : value);
    }
    Cell openingCell = missing ? missingRate : new Cell.Value(sum, baseCurrency);
    List<Cell> running = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      PostingValue posting = entry.posting();
      BigDecimal value = atRate(posting.amount(), posting.currencyCode(), baseCurrency, rates);
      missing = missing || value == null;
      if (missing) {
        running.add(missingRate);
        continue;
      }
      sum = sum.add(entry.creditNatural() ? value.negate() : value);
      running.add(new Cell.Value(sum, baseCurrency));
    }
    return new BalanceRun(openingCell, running);
  }

  /** {@code amount} in base at the period-end rate; {@code null} when its currency has none. */
  private static BigDecimal atRate(
      BigDecimal amount, String currency, String baseCurrency, Map<String, BigDecimal> rates) {
    if (currency.equals(baseCurrency)) {
      return amount;
    }
    BigDecimal rate = rates.get(currency);
    return rate == null ? null : amount.multiply(rate);
  }

  private static BalanceRun nativeClosingBalance(List<OpeningPart> openings, List<Entry> entries) {
    Cell multiCurrency = new Cell.Illegal(Cell.Reason.MULTI_CURRENCY);
    Set<String> currencies = new HashSet<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (OpeningPart opening : openings) {
      if (opening.showsInOpening()) {
        currencies.add(opening.currencyCode());
      }
      BigDecimal amount = opening.nativeAmount();
      sum = sum.add(opening.creditNatural() ? amount.negate() : amount);
    }
    // With nothing held before the period, the line is a zero in the first group's currency.
    String currency =
        currencies.isEmpty() ? openings.get(0).currencyCode() : currencies.iterator().next();
    currencies.add(currency);
    Cell openingCell = currencies.size() > 1 ? multiCurrency : new Cell.Value(sum, currency);
    List<Cell> running = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      PostingValue posting = entry.posting();
      currencies.add(posting.currencyCode());
      if (currencies.size() > 1) {
        running.add(multiCurrency);
        continue;
      }
      BigDecimal amount = posting.amount();
      sum = sum.add(entry.creditNatural() ? amount.negate() : amount);
      running.add(new Cell.Value(sum, posting.currencyCode()));
    }
    return new BalanceRun(openingCell, running);
  }

  private static List<Cell> countPostings(List<Entry> entries) {
    List<Cell> running = new ArrayList<>(entries.size());
    for (int i = 1; i <= entries.size(); i++) {
      running.add(new Cell.Count(i));
    }
    return running;
  }

  private static List<Cell> countTransactions(List<Entry> entries) {
    Set<String> seen = new HashSet<>();
    List<Cell> running = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      PostingValue posting = entry.posting();
      seen.add(entry.addend() + ":" + posting.transactionId() + ":" + posting.currencyCode());
      running.add(new Cell.Count(seen.size()));
    }
    return running;
  }

  private static List<Cell> baseTurnover(String baseCurrency, List<Entry> entries) {
    BigDecimal sum = BigDecimal.ZERO;
    boolean missingRate = false;
    List<Cell> running = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      BigDecimal base = entry.posting().baseAmount();
      missingRate = missingRate || base == null;
      if (missingRate) {
        running.add(new Cell.Illegal(Cell.Reason.MISSING_RATE));
        continue;
      }
      sum = sum.add(entry.creditNatural() ? base.negate() : base);
      running.add(new Cell.Value(sum, baseCurrency));
    }
    return running;
  }

  private static List<Cell> nativeTurnover(List<Entry> entries) {
    BigDecimal sum = BigDecimal.ZERO;
    Set<String> currencies = new HashSet<>();
    List<Cell> running = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      PostingValue posting = entry.posting();
      currencies.add(posting.currencyCode());
      if (currencies.size() > 1) {
        running.add(new Cell.Illegal(Cell.Reason.MULTI_CURRENCY));
        continue;
      }
      BigDecimal amount = posting.amount();
      sum = sum.add(entry.creditNatural() ? amount.negate() : amount);
      running.add(new Cell.Value(sum, posting.currencyCode()));
    }
    return running;
  }
}
