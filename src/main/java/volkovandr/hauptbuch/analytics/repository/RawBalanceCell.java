package volkovandr.hauptbuch.analytics.repository;

import java.math.BigDecimal;

/**
 * One raw group from a closing-balance query (reporting.md §5.1): the native standing balance of a
 * dimension value, in one currency, as of a single date. The base valuation (native × rate-as-of,
 * data-model §6.1) is applied by the engine, one rate lookup per currency — unlike turnover, a
 * balance is valued once at the report date, not posting-by-posting.
 *
 * @param dimensionKey the grouped dimension value's stable key (e.g. a top-level account id)
 * @param dimensionLabel its display label
 * @param dimensionType the backing account's {@code type}, for the credit-natural display flip
 * @param currencyCode the native currency of this balance
 * @param nativeBalance the cumulative native balance as of the query's {@code asOf} date
 */
public record RawBalanceCell(
    String dimensionKey,
    String dimensionLabel,
    String dimensionType,
    String currencyCode,
    BigDecimal nativeBalance) {}
