package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): the split panel's readout math — remaining, the pay/receive direction, and
 * the "the rest" defaulting on add-line. Mirrors the commit-side sign rule (2026-07-09) but
 * leniently, so an incomplete line contributes nothing while the user is mid-entry.
 */
class SplitPanelAssemblerTest {

  private final SplitPanelAssembler assembler = new SplitPanelAssembler();

  private static SplitForm form(String total, List<String> types, List<String> amounts) {
    List<String> blanks = amounts.stream().map(a -> "").toList();
    return new SplitForm(
        null,
        LocalDate.of(2026, 2, 1),
        1L,
        null,
        null,
        total,
        blanks,
        blanks,
        types,
        amounts,
        blanks,
        null,
        null,
        null,
        null);
  }

  @Test
  void mixedLinesNetToAnOutflowAndBalanceAgainstTheTotal() {
    // Food 20 (expense) + Deposit 3 (income): net −17, |net| 17, remaining 17 − 17 = 0.
    SplitPanel panel =
        assembler.panel(form("17,00", List.of("expense", "income"), List.of("20", "3")), null);

    assertThat(panel.netDisplay()).isEqualTo("17,00");
    assertThat(panel.remaining()).isEqualTo("0,00");
    assertThat(panel.balanced()).isTrue();
    assertThat(panel.direction()).isEqualTo("pay");
  }

  @Test
  void incomeHeavyLinesReadAsReceive() {
    SplitPanel panel =
        assembler.panel(form("0,00", List.of("expense", "income"), List.of("3", "20")), null);

    assertThat(panel.direction()).isEqualTo("receive");
    assertThat(panel.netDisplay()).isEqualTo("17,00");
  }

  @Test
  void netZeroReadsAsNoNetPayment() {
    SplitPanel panel =
        assembler.panel(form("0,00", List.of("expense", "income"), List.of("5", "5")), null);

    assertThat(panel.direction()).isEqualTo("none");
    assertThat(panel.balanced()).isTrue();
  }

  @Test
  void incompleteLineContributesNothingToTheReadout() {
    // Second line has an amount but no resolved type yet — it must not skew the sum.
    SplitPanel panel =
        assembler.panel(form("20,00", List.of("expense", ""), List.of("20", "5")), null);

    assertThat(panel.netDisplay()).isEqualTo("20,00");
    assertThat(panel.balanced()).isTrue();
  }

  @Test
  void addLineDefaultsTheNewAmountToTheRest() {
    // One 15 line against a total of 20 → the appended line defaults to 5,00.
    SplitForm grown = assembler.addLine(form("20,00", List.of("expense"), List.of("15")));

    assertThat(grown.lineAmount()).containsExactly("15", "5,00");
    assertThat(grown.lineCategoryId()).containsExactly("", "");
  }

  @Test
  void removeLineDropsTheChosenIndexAcrossEveryArray() {
    SplitForm shrunk =
        assembler.removeLine(form("20,00", List.of("expense", "income"), List.of("20", "3")), 0);

    assertThat(shrunk.lineAmount()).containsExactly("3");
    assertThat(shrunk.lineCategoryType()).containsExactly("income");
  }
}
