package volkovandr.hauptbuch.analytics;

import java.math.BigDecimal;

/**
 * One grid cell, or a total (reporting.md §7): the engine distinguishes "no postings" from
 * "postings summing to zero" from "arithmetically meaningless", and refuses to print a number for
 * the last case.
 */
// PMD's ConstantsInInterface guards against a String-constants-bag interface some unrelated class
// implements purely to import its fields — impossible here: Cell is sealed to Blank/Value/Illegal
// in this file, and the two singletons below exist so every call site can compare a stateless
// Blank/Illegal cell by reference instead of allocating one to test equality against.
@SuppressWarnings("PMD.ConstantsInInterface")
public sealed interface Cell {

  /** The shared "no postings" instance — {@link Blank} carries no fields. */
  Cell BLANK = new Blank();

  /** No postings matched — rendered blank (§7.3). */
  record Blank() implements Cell {}

  /**
   * A real, printable figure — including a legitimate zero (postings that summed to nothing).
   * {@code currencyCode} is the base currency for a {@link PresentationCurrency#BASE} measure, or
   * the single native currency for a {@link PresentationCurrency#ACCOUNT} one — always known,
   * because a cell spanning more than one native currency is {@link Illegal}, never a {@link Value}
   * (§5.4).
   */
  record Value(BigDecimal amount, String currencyCode) implements Cell {
    public Value {
      if (amount == null) {
        throw new IllegalArgumentException("A Value cell needs an amount.");
      }
      if (currencyCode == null) {
        throw new IllegalArgumentException("A Value cell needs a currency.");
      }
    }
  }

  /**
   * The aggregate would be arithmetically meaningless — rendered {@code —} (§7.2). Never a number.
   * {@code reason} is why, so the table renderer's help marker (§11a.7) can name it rather than
   * leave every dash equally unexplained.
   */
  record Illegal(Reason reason) implements Cell {
    public Illegal {
      if (reason == null) {
        throw new IllegalArgumentException("An Illegal cell needs a reason.");
      }
    }
  }

  /**
   * Why a cell or total is {@link Illegal} — reporting.md §7.2, extended by two data-driven cases
   * the doc's own three situations don't name: a missing exchange rate, and a total spanning
   * multiple measures (e.g. base next to native) rather than multiple accounts.
   */
  enum Reason {
    /** A closing balance summed along the time axis — a stock has no "sum of two moments". */
    TIME_AXIS_BALANCE,
    /** An account-currency cell or total spanning more than one native currency. */
    MULTI_CURRENCY,
    /** A total across tag rows/columns — tags overlap, so it would double-count a posting. */
    CROSS_TAG_TOTAL,
    /** No exchange rate is recorded on or before the valuation date. */
    MISSING_RATE,
    /** A row/column total summing across more than one measure — different views, not addends. */
    MULTI_MEASURE_TOTAL
  }

  /**
   * A count measure's value (§5.5) — postings or distinct transactions, never a money amount, so it
   * carries no currency and is never negated by the credit-natural display flip.
   */
  record Count(long count) implements Cell {}
}
