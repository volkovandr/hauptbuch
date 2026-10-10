package volkovandr.hauptbuch.statements.repository;

import java.math.BigDecimal;

/**
 * The foreign charge a bank printed on a line (statements.md §3.3): the amount in the charge's own
 * currency and that currency's code. The printed rate is not carried — the real rate follows from
 * the two amounts.
 *
 * @param amount the signed amount in {@code currencyCode}
 * @param currencyCode the ISO code of the charge's currency
 */
public record OriginalCharge(BigDecimal amount, String currencyCode) {}
