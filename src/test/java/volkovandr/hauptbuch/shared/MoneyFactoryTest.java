package volkovandr.hauptbuch.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.joda.money.Money;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): no container, pure logic. Pins the construction contract
 * of {@link MoneyFactory} so the rest of the engine can rely on it.
 */
class MoneyFactoryTest {

	@Test
	void buildsMoneyFromDecimalAndIsoCode() {
		Money money = MoneyFactory.of(new BigDecimal("12.34"), "EUR");

		assertThat(money.getCurrencyUnit().getCode()).isEqualTo("EUR");
		assertThat(money.getAmount()).isEqualByComparingTo("12.34");
	}

	@Test
	void roundsHalfUpToTheCurrencysMinorUnits() {
		// EUR has 2 minor units; the third decimal rounds away.
		assertThat(MoneyFactory.of(new BigDecimal("1.005"), "EUR").getAmount())
				.isEqualByComparingTo("1.01");
	}

	@Test
	void zeroIsCurrencyTyped() {
		assertThat(MoneyFactory.zero("CHF").isZero()).isTrue();
		assertThat(MoneyFactory.zero("CHF").getCurrencyUnit().getCode()).isEqualTo("CHF");
	}

	@Test
	void rejectsUnknownCurrencyCode() {
		assertThatThrownBy(() -> MoneyFactory.of(BigDecimal.ONE, "ZZZ"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
