package volkovandr.hauptbuch.analytics.repository;

import java.math.BigDecimal;

/**
 * One account's native closing balance as of a date — a raw export's leaf-grain closing balance
 * (reporting.md §13), valued by the engine exactly as a {@link RawBalanceCell}.
 *
 * @param accountId the account
 * @param currencyCode its currency
 * @param nativeBalance the cumulative native balance as of the query's date
 */
public record LeafBalanceFact(long accountId, String currencyCode, BigDecimal nativeBalance) {}
