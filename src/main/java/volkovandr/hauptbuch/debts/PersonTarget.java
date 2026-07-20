package volkovandr.hauptbuch.debts;

import java.util.Optional;

/**
 * The person-attribution vocabulary the register's Category field carries (register §3.5, plan
 * stage 8b, data-model §7): picking {@code for <person>} / {@code by <person>} routes the
 * counter-leg to that person's per-currency debt leaf instead of a category, riding the transfer
 * commit path. The reserved keywords sit beside {@link
 * volkovandr.hauptbuch.ledger.TransferTarget}'s {@code To →}/{@code From ←} and dodge the
 * person-vs-account name clash by using a distinct, bare-word prefix (no arrow) — the design
 * accepts the low-probability risk of a category name itself starting with "for "/"by " (register
 * §3.5 rationale).
 *
 * <p>The option value is {@code "for Name"} / {@code "by Name"} — the person's display name only,
 * which {@link #parse} reads back so the counterpart person can be resolved by name.
 */
public final class PersonTarget {

  private static final String FOR_PREFIX = "for ";
  private static final String BY_PREFIX = "by ";

  private PersonTarget() {}

  /** Who funded the transaction, relative to the funding account (register §3.5). */
  public enum Direction {
    /** You funded it — the person owes you more. Books {@code → Person} (an outflow). */
    FOR,
    /** The person funded it — you owe them more. Books {@code Person →} (an inflow). */
    BY
  }

  /** The datalist option value for {@code direction} attributed to {@code personName}. */
  public static String option(Direction direction, String personName) {
    return (direction == Direction.FOR ? FOR_PREFIX : BY_PREFIX) + personName;
  }

  /**
   * Parse a Category-field value into a person target, or empty when it carries no {@code for }/
   * {@code by } prefix (a plain category or transfer target).
   */
  public static Optional<Parsed> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String trimmed = text.strip();
    if (trimmed.startsWith(FOR_PREFIX)) {
      String name = trimmed.substring(FOR_PREFIX.length()).strip();
      return name.isEmpty() ? Optional.empty() : Optional.of(new Parsed(Direction.FOR, name));
    }
    if (trimmed.startsWith(BY_PREFIX)) {
      String name = trimmed.substring(BY_PREFIX.length()).strip();
      return name.isEmpty() ? Optional.empty() : Optional.of(new Parsed(Direction.BY, name));
    }
    return Optional.empty();
  }

  /** A parsed person target: its direction and the counterpart person's display name. */
  public record Parsed(Direction direction, String personName) {}
}
