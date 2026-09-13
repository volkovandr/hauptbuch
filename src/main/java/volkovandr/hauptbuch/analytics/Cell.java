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

  /** The shared "meaningless aggregate" instance — {@link Illegal} carries no fields. */
  Cell ILLEGAL = new Illegal();

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
   */
  record Illegal() implements Cell {}

  /**
   * A count measure's value (§5.5) — postings or distinct transactions, never a money amount, so it
   * carries no currency and is never negated by the credit-natural display flip.
   */
  record Count(long count) implements Cell {}
}
