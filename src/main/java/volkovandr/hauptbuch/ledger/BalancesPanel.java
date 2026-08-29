package volkovandr.hauptbuch.ledger;

import java.util.List;

/**
 * The render model for the landing page's Balances panel (CONTEXT.md "Balances panel", issue
 * landing-page/01): the pinned accounts with their balances and an optional base-currency total.
 *
 * <p>The controller adds this attribute only when the panel should render — base currency set and
 * at least one pinned account surviving the closed/deleted filter — so the template never has to
 * decide whether to draw the panel at all (mirrors the tracking-stats fragment's {@code stats}
 * attribute).
 *
 * @param rows one line per pinned account, ordered alphabetically by name
 * @param totalShown whether to render the Total row — true only when two or more accounts are
 *     pinned <em>and</em> every non-base account could be valued; a missing rate suppresses it
 *     entirely rather than showing a partial total as whole ({@code PeopleOverviewService}'s rule)
 * @param total the base-currency sum of every row's base value, German-formatted and bare;
 *     meaningful only when {@code totalShown}
 * @param baseCurrencyCode the book's base currency code, for labelling the bare total
 */
public record BalancesPanel(
    List<Row> rows, boolean totalShown, String total, String baseCurrencyCode) {

  /** Defensively copy the row list to an immutable list. */
  public BalancesPanel {
    rows = List.copyOf(rows);
  }

  /**
   * One pinned account's line.
   *
   * @param accountId the account's id — the row links to {@code /register?accountId=<id>}
   * @param hue the stored register hue for the colour tick; nullable
   * @param name the account's display name
   * @param amount the fully-rendered balance: bare for a base-currency account ({@code 1.234,56}),
   *     native-with-symbol plus a bracketed bare base equivalent for a non-base account ({@code
   *     10.000,00 CHF (9.200,00)}), or a {@code (—)} bracket when the account has no exchange rate
   * @param negative whether the native balance is negative — drives the oxblood ink, as in the
   *     register and People views
   */
  public record Row(long accountId, Integer hue, String name, String amount, boolean negative) {}
}
