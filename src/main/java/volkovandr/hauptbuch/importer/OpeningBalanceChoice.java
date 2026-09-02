package volkovandr.hauptbuch.importer;

/**
 * The opening-balance reconciliation outcome recorded on an {@code import_account} row (import.md
 * §5.1; plan c3), stored verbatim in the {@code import_account.opening_balance_choice} check
 * constraint.
 *
 * <p>Money exports an account's opening balance as a self-transfer and the target Hauptbuch account
 * usually already has one of its own. The owner picks the winner: {@link #KEEP_HAUPTBUCH} ignores
 * Money's, {@link #TAKE_MONEY} voids Hauptbuch's own at commit and books Money's, {@link #OVERRIDE}
 * books an explicit amount the owner typed ({@code opening_balance_amount}). Coded as {@code
 * String} constants to match the project's text-coded column convention, the same choice {@link
 * ImportSessionState} makes.
 */
public final class OpeningBalanceChoice {

  /** Keep the target Hauptbuch account's existing opening balance; drop Money's. */
  public static final String KEEP_HAUPTBUCH = "keep_hauptbuch";

  /** Void Hauptbuch's own opening balance at commit and book Money's staged one. */
  public static final String TAKE_MONEY = "take_money";

  /** Book an explicit amount the owner typed, in place of either side's. */
  public static final String OVERRIDE = "override";

  private OpeningBalanceChoice() {}

  /** Whether {@code choice} is one of the three accepted outcomes. */
  static boolean isValid(String choice) {
    return KEEP_HAUPTBUCH.equals(choice) || TAKE_MONEY.equals(choice) || OVERRIDE.equals(choice);
  }
}
