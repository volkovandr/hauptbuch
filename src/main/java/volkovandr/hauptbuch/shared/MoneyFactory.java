package volkovandr.hauptbuch.shared;

import java.math.BigDecimal;

import org.joda.money.CurrencyUnit;
import org.joda.money.Money;

/**
 * The one sanctioned way to construct {@link Money} in this codebase.
 *
 * <p>This is intentionally a <em>thin</em> wrapper (plan stage 2): it does not hide
 * Joda-Money — call sites still pass {@link Money} around — it only centralises
 * construction so that the rounding mode and the ISO-4217 currency lookup are
 * decided in exactly one place. The DB stores monetary values as {@code numeric}
 * and currencies as their ISO code (data-model §3.0/§3.1); this turns that pair
 * back into a typed {@link Money}.
 *
 * <p>Rounding is {@link java.math.RoundingMode#HALF_UP HALF_UP} to each currency's
 * minor units. The double-entry engine never relies on this to fix an imbalance —
 * postings sum to zero by construction (CLAUDE.md §4); rounding here only adapts a
 * decimal to a currency's scale at the boundary.
 */
public final class MoneyFactory {

	private MoneyFactory() {
	}

	/**
	 * Build {@link Money} from a decimal amount and an ISO-4217 currency code,
	 * rounding {@code HALF_UP} to the currency's minor units.
	 *
	 * @param amount       the value, e.g. read from a {@code numeric} column
	 * @param currencyCode ISO-4217 code, e.g. {@code "EUR"}, {@code "CHF"}
	 */
	public static Money of(BigDecimal amount, String currencyCode) {
		return Money.of(CurrencyUnit.of(currencyCode), amount, java.math.RoundingMode.HALF_UP);
	}

	/** Zero in the given ISO-4217 currency. */
	public static Money zero(String currencyCode) {
		return Money.zero(CurrencyUnit.of(currencyCode));
	}
}
