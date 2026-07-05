package volkovandr.hauptbuch.ledger;

import java.util.List;

/**
 * The whole register screen as one immutable view model (register §2) — the rows to render plus the
 * state the filter form needs to show what is active. Assembled by {@link RegisterService} so the
 * controller stays thin and the template does no computation beyond iteration and formatting.
 *
 * @param rows the register rows, oldest-first (newest at the bottom — register §2.1)
 * @param accounts every account offered in the account multi-select, in list order
 * @param payees every payee offered in the payee filter, alphabetical
 * @param filter the currently-applied filter (selected accounts, date range, payee), for redisplay
 */
public record RegisterView(
    List<RegisterRowView> rows,
    List<RegisterAccountOption> accounts,
    List<RegisterPayeeOption> payees,
    RegisterFilter filter) {

  /** Defensively copy the row and option lists to immutable lists. */
  public RegisterView {
    rows = List.copyOf(rows);
    accounts = List.copyOf(accounts);
    payees = List.copyOf(payees);
  }

  /**
   * One account offered in the register's account multi-select (register §2.3), carrying whether it
   * is currently selected so the form redisplays the active choice.
   *
   * @param accountId the account
   * @param name display name
   * @param hue stored register hue (for the swatch); null on accounts with no thread colour
   * @param currencyCode the account's currency
   * @param selected whether this account is in the applied filter
   */
  public record RegisterAccountOption(
      long accountId, String name, Integer hue, String currencyCode, boolean selected) {}

  /**
   * One payee offered in the register's payee filter, carrying whether it is the active choice.
   *
   * @param payeeId the payee
   * @param name display name
   * @param selected whether this payee is the applied filter
   */
  public record RegisterPayeeOption(long payeeId, String name, boolean selected) {}
}
