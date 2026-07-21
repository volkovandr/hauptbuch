package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import java.util.List;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;

/**
 * The render model for the settle-up launcher (plan stage 8e, data-model §7): a per-person,
 * per-currency form that zeroes one debt position through a single, dated transfer between a real
 * own account and the person's debt leaf. The <em>direction is derived from the live balance
 * sign</em> (positive = they owe you, so they pay you in; negative = you owe them, so you pay out)
 * and is not a field the user sets — the form only collects the account, date, and amount(s).
 *
 * <p>Assembled in {@code operations} because it reaches {@code debts} (the person and their leaf),
 * {@code accounts} (the funding-account picker), and {@code ledger} (the cross-currency field
 * layout) at once, and commits through {@code operations}' own {@link DockCommitService} — the same
 * boundary reason the entry dock's controllers live here (plan stage 7 boundary note).
 *
 * <p>The amount fields ride the register's {@link CrossCurrencyFields} model (register §3.8a): one
 * Amount field when the funding account is in the debt currency; a second (the person-leg native
 * amount, defaulted to the outstanding figure) when it is not; and a third base amount only when
 * neither leg is the book's base currency. {@code fundingAmountText} is the paying leg's default —
 * the whole outstanding figure in the single-currency case, blank when cross-currency (what
 * actually left the account is a real fact only the user knows).
 *
 * @param personId the person being settled with (the form's path scope)
 * @param personName the person's display name
 * @param currencyCode the debt currency this launcher settles (never netted across currencies)
 * @param summary the directional one-liner, e.g. {@code "You owe Max 10,00 CHF"}
 * @param youOwe whether the balance is negative (you pay out); drives the button wording
 * @param accounts the pickable own accounts to fund the settle from (person leaves excluded)
 * @param date the settle date, defaulting to today
 * @param fields the cross-currency amount-field layout for the chosen funding account vs the debt
 *     currency
 * @param fundingAmountText the paying Amount field's default value (outstanding when
 *     single-currency, blank when cross-currency)
 */
public record SettleUpView(
    long personId,
    String personName,
    String currencyCode,
    String summary,
    boolean youOwe,
    List<AccountOption> accounts,
    LocalDate date,
    CrossCurrencyFields fields,
    String fundingAmountText) {

  /** Defensively copy the account list to an immutable list (SpotBugs EI_EXPOSE). */
  public SettleUpView {
    accounts = List.copyOf(accounts);
  }

  /**
   * One funding-account option: its id, its {@code Name (CUR)} label, and whether it is the
   * currently chosen account (pre-selected in the picker).
   *
   * @param accountId the own account's id
   * @param label the display label, {@code Name (CUR)}
   * @param selected whether this option is the chosen funding account
   */
  public record AccountOption(long accountId, String label, boolean selected) {}
}
