package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A rate row in the sparse, carry-forward exchange-rate cache (data-model §3.7).
 *
 * <p>{@code rate} is units of <em>base</em> per 1 unit of {@code currencyCode}. The table is a
 * lookup cache only — it proposes rates on entry and revalues held foreign balances into base for
 * net worth. It <em>never</em> rewrites a booked conversion; those are frozen on the posting's
 * {@code baseAmount}.
 *
 * @param exchangeRateId surrogate PK; null for a not-yet-persisted rate
 * @param currencyCode the foreign currency this rate is for
 * @param date the date the rate is valid from (carried forward until superseded)
 * @param rate units of base per 1 unit of {@code currencyCode}
 * @param source one of {@code ecb | manual}
 */
public record ExchangeRate(
    Long exchangeRateId, String currencyCode, LocalDate date, BigDecimal rate, String source) {}
