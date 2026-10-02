package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): the mapping between the dock's Amount / Off account fields and its two leg
 * amounts (issue transaction-register-ui/04). The explicit sign (register §3.8) is typed on the
 * Amount but belongs to the funding leg — it must move across both ways, or re-saving an untouched
 * cross-currency refund silently flips it.
 */
class DockAmountTextsTest {

  @Test
  void fundingTextIsTheOffAccountMagnitude() {
    assertThat(DockAmountTexts.fundingText("10", "9,10")).isEqualTo("9,10");
  }

  @Test
  void fundingTextCarriesTheSignTypedOnTheAmount() {
    assertThat(DockAmountTexts.fundingText("−10", "9,10")).isEqualTo("−9,10");
    assertThat(DockAmountTexts.fundingText("-10", "9,10")).isEqualTo("-9,10");
    assertThat(DockAmountTexts.fundingText("+10", "9,10")).isEqualTo("+9,10");
  }

  @Test
  void counterpartTextIsTheAmountWithoutItsSign() {
    assertThat(DockAmountTexts.counterpartText("10")).isEqualTo("10");
    assertThat(DockAmountTexts.counterpartText(" − 10 ")).isEqualTo("10");
  }

  @Test
  void blankAmountsMapToEmptyText() {
    assertThat(DockAmountTexts.counterpartText(null)).isEmpty();
    assertThat(DockAmountTexts.fundingText(null, null)).isEmpty();
  }

  @Test
  void editAmountTextMovesTheFundingSignOntoTheCounterpartMagnitude() {
    assertThat(DockAmountTexts.amountText("9,10", "10,00")).isEqualTo("10,00");
    assertThat(DockAmountTexts.amountText("−9,10", "10,00")).isEqualTo("−10,00");
  }

  @Test
  void editOffAccountTextIsTheFundingMagnitude() {
    assertThat(DockAmountTexts.offAccountText("−9,10")).isEqualTo("9,10");
    assertThat(DockAmountTexts.offAccountText("9,10")).isEqualTo("9,10");
  }

  @Test
  void roundTripPreservesBothAmountsAndTheSign() {
    String amount = DockAmountTexts.amountText("−9,10", "10,00");
    String offAccount = DockAmountTexts.offAccountText("−9,10");

    assertThat(DockAmountTexts.fundingText(amount, offAccount)).isEqualTo("−9,10");
    assertThat(DockAmountTexts.counterpartText(amount)).isEqualTo("10,00");
  }
}
