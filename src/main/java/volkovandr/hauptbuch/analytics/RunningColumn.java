package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import volkovandr.hauptbuch.analytics.repository.PostingValue;

/**
 * A drill-down list's running column (reporting.md §12): the figure's measure accumulated row by
 * row, following the figure's own arithmetic so the last row lands on it — each posting flipped as
 * its cell is (data-model §4.1), a base figure turning {@code —} at the first posting with no rate,
 * a native one at the second currency, and a transaction counted once per cell and currency (the
 * way {@link CellValuation} sums its per-currency groups).
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
          throw new IllegalArgumentException("A closing balance has no running turnover column.");
    };
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
