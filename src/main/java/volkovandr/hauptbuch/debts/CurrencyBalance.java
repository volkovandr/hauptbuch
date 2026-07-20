package volkovandr.hauptbuch.debts;

import java.math.BigDecimal;

/**
 * One currency's signed net balance for a person (data-model §7, plan stage 8d) — a single cell of
 * the People-balances view. The <em>sign is the direction</em>: positive means the person owes you
 * (the leaf behaves as an asset); negative means you owe the person. Currencies are never netted
 * against each other, so a person's position is a small list of these, one per currency they carry
 * a non-zero balance in.
 *
 * @param currencyCode ISO-4217 code of the debt leaf
 * @param signedBalance the summed native balance across that person's leaf in this currency
 */
public record CurrencyBalance(String currencyCode, BigDecimal signedBalance) {}
