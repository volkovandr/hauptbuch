package volkovandr.hauptbuch.accounts;

/**
 * The {@code Name (CUR)} label the dock's Account field offers and parses back (register §3.3, plan
 * stage 8b.1) — <em>both halves in one place</em>, because they are a round-trip: the datalist
 * renders {@link #format}, the user picks it, and the resolver reads it with {@link #parse}. Split
 * across modules they drift, and a drifted label means a picked account silently fails to resolve.
 *
 * <p>Lives in {@code accounts} because every module that names an account — {@code ledger}'s
 * register view, {@code operations}' resolver and dock pre-fills — already depends on it.
 */
public final class AccountEntryLabel {

  private AccountEntryLabel() {}

  /**
   * The label for an account: {@code Cash (EUR)}. {@code name} may be the account's full {@code
   * Parent - Leaf} path; only the leaf's currency is named, once at the end. A name that already
   * ends with the account's own currency code as a separate word ({@code BankAaa-EUR}, {@code Card
   * (EUR)}) gets no second suffix (issue transaction-register-ui/25).
   */
  public static String format(String name, String currencyCode) {
    if (endsWithCurrency(name, currencyCode)) {
      return name;
    }
    return name + " (" + currencyCode + ")";
  }

  /** The label for an account. */
  public static String format(Account account) {
    return format(account.name(), account.currencyCode());
  }

  /**
   * Whether {@code name} ends with {@code currencyCode} as its own word: case-insensitive, not
   * preceded by a letter, optionally closed by a {@code )}. {@code Fleur} does not end with {@code
   * EUR}.
   */
  private static boolean endsWithCurrency(String name, String currencyCode) {
    String text = name.endsWith(")") ? name.substring(0, name.length() - 1) : name;
    int start = text.length() - currencyCode.length();
    if (start < 0 || !text.substring(start).equalsIgnoreCase(currencyCode)) {
      return false;
    }
    return start == 0 || !Character.isLetter(text.charAt(start - 1));
  }

  /**
   * Split a typed label back into its parts. The {@code (CUR)} suffix is <em>optional</em> — typing
   * a bare {@code Cash} resolves — and when present it disambiguates same-named accounts in
   * different currencies. Text that is not a well-formed suffix is treated as part of the name, so
   * an account genuinely called {@code "Petty (cash)"} still resolves by its own name.
   */
  public static Parsed parse(String text) {
    int open = text.lastIndexOf('(');
    if (open > 0 && text.endsWith(")")) {
      String currency = text.substring(open + 1, text.length() - 1).strip();
      String name = text.substring(0, open).strip();
      if (!currency.isBlank() && !name.isBlank()) {
        return new Parsed(name, currency);
      }
    }
    return new Parsed(text, null);
  }

  /**
   * A parsed label.
   *
   * @param name the account name
   * @param currencyCode the currency the label named, or {@code null} when it carried no suffix
   */
  public record Parsed(String name, String currencyCode) {}
}
