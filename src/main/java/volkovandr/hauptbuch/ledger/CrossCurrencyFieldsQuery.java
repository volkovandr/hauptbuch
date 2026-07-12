package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;

/**
 * The inputs to {@link CrossCurrencyFieldsService#resolve} — bundled into one record rather than a
 * long parameter list, mirroring the project's {@code *Draft}/{@code *Entry} convention for a
 * cohesive set of fields.
 *
 * @param fundingCurrencyCode the paying account's currency
 * @param categoryCurrencyCode the selected leaf currency; null/blank means no override yet
 * @param date the transaction date, to look up the rate as of it; may be null
 * @param fundingAmountText the funding leg's currently-typed sign-free amount, for the base-amount
 *     rate prefill; may be null
 * @param categoryAmountText the category-leg amount field's redisplay value; may be null
 * @param baseAmountText the base-amount field's redisplay value; null triggers the rate prefill
 */
public record CrossCurrencyFieldsQuery(
    String fundingCurrencyCode,
    String categoryCurrencyCode,
    LocalDate date,
    String fundingAmountText,
    String categoryAmountText,
    String baseAmountText) {}
