package volkovandr.hauptbuch.ledger;

/**
 * The entry dock's amount-field layout for a category-currency selection (register §3.5/§3.8a) —
 * how many amount fields the dock shows beside the always-present funding Amount field, and what to
 * pre-fill or redisplay in them. Lives in {@code ledger} because it depends only on {@code
 * ledger}-owned reads (settings, exchange rates); both {@code ledger}'s own register screen (the
 * untouched single-currency default) and {@code operations}' dock controller (the real overrides)
 * consume it.
 *
 * @param fundingCurrencyCode the paying account's currency (the funding Amount field's label)
 * @param categoryCurrencyCode the resolved leaf currency — defaults to the funding currency
 * @param crossCurrency whether the category currency differs from the funding currency, revealing
 *     the category-leg native amount field
 * @param neitherIsBase whether a third, separate base-amount field is also needed (cross-currency
 *     and neither leg is the book's base currency)
 * @param categoryAmountText the category-leg native amount field's redisplay value; null when the
 *     field is not shown
 * @param baseAmountText the base-amount field's pre-filled or redisplayed value; null when the
 *     field is not shown or no rate/prior value is available
 */
public record CrossCurrencyFields(
    String fundingCurrencyCode,
    String categoryCurrencyCode,
    boolean crossCurrency,
    boolean neitherIsBase,
    String categoryAmountText,
    String baseAmountText) {

  /** The untouched single-currency case: no override yet, so no extra fields. */
  public static CrossCurrencyFields singleCurrency(String currencyCode) {
    return new CrossCurrencyFields(currencyCode, currencyCode, false, false, null, null);
  }
}
