package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): what the dock's Amount and Off account fields show for a loaded
 * transaction (issue transaction-register-ui/04) — the Amount is always in the transaction
 * currency.
 */
class DockEditModelTest {

  private static DockEditModel model(String amount, String categoryAmount) {
    return new DockEditModel(
        1L,
        LocalDate.of(2026, 2, 1),
        2L,
        "Cash (EUR)",
        null,
        amount,
        3L,
        "Food",
        categoryAmount == null ? null : "CHF",
        categoryAmount,
        null,
        null,
        null,
        List.of());
  }

  @Test
  void singleCurrencyShowsTheFundingAmountAndNoOffAccount() {
    DockEditModel edit = model("−20,00", null);

    assertThat(edit.amountFieldText()).isEqualTo("−20,00");
    assertThat(edit.offAccountFieldText()).isNull();
  }

  @Test
  void crossCurrencyShowsTheCounterpartAmountWithTheSignAndTheFundingAsOffAccount() {
    DockEditModel edit = model("−9,10", "10,00");

    assertThat(edit.amountFieldText()).isEqualTo("−10,00");
    assertThat(edit.offAccountFieldText()).isEqualTo("9,10");
  }

  @Test
  void stickyPrefillWithoutAmountsShowsNothing() {
    DockEditModel edit = model(null, null);

    assertThat(edit.amountFieldText()).isNull();
    assertThat(edit.offAccountFieldText()).isNull();
  }
}
