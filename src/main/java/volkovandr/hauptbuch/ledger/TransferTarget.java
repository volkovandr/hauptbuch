package volkovandr.hauptbuch.ledger;

import java.util.Optional;

/**
 * The transfer-target vocabulary the register's Category field carries (register §3.5/§3.8, plan
 * stage 7d.3): picking {@code To → <account>} / {@code From ← <account>} routes the counter-leg to
 * a <em>real account</em> instead of a category, turning the entry into a transfer between two own
 * accounts. The glyphs live here, in {@code ledger} (which owns the register), so the datalist that
 * offers the options ({@link RegisterService}) and the resolver that reads them back (the {@code
 * categories} counterpart resolver) share one definition rather than duplicating the strings.
 *
 * <p>The option value is {@code "To → <label>"} / {@code "From ← <label>"}, where the label is the
 * account's picker label ({@code BankAaa - Credit card (EUR)}, issue transaction-register-ui/25) —
 * the currency is what tells two same-named accounts apart. {@link #parse} reads the label back so
 * the counterpart can be resolved among the post-to set.
 */
public final class TransferTarget {

  private static final String TO_PREFIX = "To → ";
  private static final String FROM_PREFIX = "From ← ";

  private TransferTarget() {}

  /** The direction of a transfer relative to the funding account (register §3.8). */
  public enum Direction {
    /** Funds leave the funding account for the counterpart account — an outflow ({@code −}). */
    TO,
    /** Funds enter the funding account from the counterpart account — an inflow ({@code +}). */
    FROM
  }

  /** The datalist option value for a transfer to/from the account {@code accountLabel} names. */
  public static String option(Direction direction, String accountLabel) {
    return (direction == Direction.TO ? TO_PREFIX : FROM_PREFIX) + accountLabel;
  }

  /**
   * Parse a Category-field value into a transfer target, or empty when it is a plain category (no
   * {@code To →} / {@code From ←} prefix). The returned {@link Parsed#accountName()} is the raw
   * name to resolve among the own accounts.
   */
  public static Optional<Parsed> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String trimmed = text.strip();
    if (trimmed.startsWith(TO_PREFIX)) {
      return Optional.of(new Parsed(Direction.TO, trimmed.substring(TO_PREFIX.length()).strip()));
    }
    if (trimmed.startsWith(FROM_PREFIX)) {
      return Optional.of(
          new Parsed(Direction.FROM, trimmed.substring(FROM_PREFIX.length()).strip()));
    }
    return Optional.empty();
  }

  /** A parsed transfer target: its direction and the counterpart account's display name. */
  public record Parsed(Direction direction, String accountName) {}
}
