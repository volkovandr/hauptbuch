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
    List<RegisterCategoryOption> categories,
    RegisterFilter filter) {

  /** Defensively copy the row and option lists to immutable lists. */
  public RegisterView {
    rows = List.copyOf(rows);
    accounts = List.copyOf(accounts);
    payees = List.copyOf(payees);
    categories = List.copyOf(categories);
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
   * One payee offered in the register's pickers, carrying whether it is the active filter choice.
   *
   * @param payeeId the payee
   * @param name the {@code Name · City · Country} display label (distinguishes same-named payees —
   *     register §3.4)
   * @param entryValue the {@code Name - City - Country} value the dock datalist offers, which the
   *     create-new parser round-trips back to this payee (so re-picking reuses it)
   * @param selected whether this payee is the applied filter
   */
  public record RegisterPayeeOption(
      long payeeId, String name, String entryValue, boolean selected) {}

  /**
   * One category offered in the dock's category datalist (register §3.5) — the semantic categories
   * a new transaction can be filed under. The dock resolves the picked name back to an id (or
   * creates a {@code Parent - Child}) through the {@code categories} module.
   *
   * @param accountId the category account
   * @param name display name
   */
  public record RegisterCategoryOption(long accountId, String name) {}
}
