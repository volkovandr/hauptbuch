package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): the {@code Name (CUR)} label the own-account pickers offer (register §3.3,
 * issue transaction-register-ui/25) — the suffix names the account's currency once, and never twice
 * when the name already carries it.
 */
class AccountEntryLabelTest {

  @Test
  void appendsTheCurrencySuffix() {
    assertThat(AccountEntryLabel.format("Cash", "EUR")).isEqualTo("Cash (EUR)");
  }

  @Test
  void suffixesOnlyTheLeafOfPath() {
    assertThat(AccountEntryLabel.format("BankAaa - Credit card", "EUR"))
        .isEqualTo("BankAaa - Credit card (EUR)");
  }

  @Test
  void addsNoSecondSuffixWhenTheNameEndsWithItsOwnCurrency() {
    assertThat(AccountEntryLabel.format("BankAaa-EUR", "EUR")).isEqualTo("BankAaa-EUR");
    assertThat(AccountEntryLabel.format("Cash eur", "EUR")).isEqualTo("Cash eur");
    assertThat(AccountEntryLabel.format("Card (EUR)", "EUR")).isEqualTo("Card (EUR)");
  }

  @Test
  void stillSuffixesNameEndingInAnotherCurrency() {
    assertThat(AccountEntryLabel.format("Travel-EUR", "CHF")).isEqualTo("Travel-EUR (CHF)");
  }

  @Test
  void stillSuffixesNameWhoseLastWordMerelyEndsInTheCode() {
    assertThat(AccountEntryLabel.format("Fleur", "EUR")).isEqualTo("Fleur (EUR)");
  }
}
