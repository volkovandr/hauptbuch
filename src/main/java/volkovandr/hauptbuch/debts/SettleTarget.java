package volkovandr.hauptbuch.debts;

import java.math.BigDecimal;

/**
 * A person's outstanding position in one currency, resolved for the settle-up launcher (plan stage
 * 8e, data-model §7): the per-currency debt leaf to zero and its current signed balance. The
 * <em>sign is the direction</em> (like {@link CurrencyBalance}): positive means the person owes you
 * (settling brings money in), negative means you owe the person (settling pays money out). Both the
 * leaf account id and the sign are read fresh at launch, so a settle always targets the real leaf
 * and picks its direction from the live balance rather than a stale figure.
 *
 * @param accountId the person's per-currency debt-leaf account — the settle transaction's
 *     counterpart leg
 * @param currencyCode ISO-4217 code of that leaf
 * @param signedBalance the current summed native balance of the leaf; never zero when the launcher
 *     is reached from a non-zero People-page figure, but carried signed so the caller derives the
 *     settle direction from it
 */
public record SettleTarget(long accountId, String currencyCode, BigDecimal signedBalance) {}
