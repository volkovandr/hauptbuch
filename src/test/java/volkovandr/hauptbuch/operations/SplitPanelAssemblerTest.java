package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Unit tier (plan §1.5): the split panel's readout math — remaining, the pay/receive direction, the
 * "the rest" defaulting on add-line, and (plan stage 7d.2) the cross-currency per-line derived
 * equivalents and the per-currency remainings that converge together. Mirrors the commit-side math
 * (2026-07-09/2026-07-13) but leniently, so an incomplete line contributes nothing mid-entry.
 */
@ExtendWith(MockitoExtension.class)
class SplitPanelAssemblerTest {

  private static final String EUR = "EUR";
  private static final String USD = "USD";
  private static final String CHF = "CHF";
  private static final long CASH_ID = 1L;

  @Mock private AccountService accountService;
  @Mock private SettingsService settingsService;

  private SplitPanelAssembler assembler;

  @BeforeEach
  void setUp() {
    assembler = new SplitPanelAssembler(accountService, settingsService);
    lenient().when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, EUR)));
    lenient().when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
  }

  private static Account account(long id, String currency) {
    return new Account(id, "n", "asset", null, currency, null, null, null, null, false);
  }

  private static SplitForm form(String total, List<String> types, List<String> amounts) {
    return crossForm(total, null, null, null, types, amounts);
  }

  /** A form with explicit per-line transfer directions (blank for a category line). */
  private static SplitForm form(
      String total,
      String spendingCurrency,
      String fundingTotal,
      String baseTotal,
      List<String> types,
      List<String> directions,
      List<String> amounts) {
    List<String> blanks = amounts.stream().map(a -> "").toList();
    return new SplitForm(
        null,
        LocalDate.of(2026, 2, 1),
        CASH_ID,
        null,
        null,
        total,
        spendingCurrency,
        fundingTotal,
        baseTotal,
        blanks,
        blanks,
        types,
        directions,
        amounts,
        blanks,
        null,
        null,
        null,
        null);
  }

  private static SplitForm crossForm(
      String total,
      String spendingCurrency,
      String fundingTotal,
      String baseTotal,
      List<String> types,
      List<String> amounts) {
    List<String> blanks = amounts.stream().map(a -> "").toList();
    return form(total, spendingCurrency, fundingTotal, baseTotal, types, blanks, amounts);
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
    assertThat(panel.currency().crossCurrency()).isFalse();
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
  void transferLinesSignTheReadoutByDirection() {
    // Food 20 (expense) + a To-transfer 50 to another account: both are outflows, net −70. The
    // transfer line has no category type — its direction signs it (register §3.8, plan stage 7d.3).
    SplitPanel panel =
        assembler.panel(
            form(
                "70,00",
                null,
                null,
                null,
                List.of("expense", ""),
                List.of("", "TO"),
                List.of("20", "50")),
            null);

    assertThat(panel.netDisplay()).isEqualTo("70,00");
    assertThat(panel.direction()).isEqualTo("pay");
    assertThat(panel.balanced()).isTrue();
    assertThat(panel.lines().get(1).transferDirection()).isEqualTo("TO");
  }

  @Test
  void fromTransferLineReadsAsAnInflow() {
    // A single From-transfer of 50 pulls funds in → net +50, direction receive.
    SplitPanel panel =
        assembler.panel(
            form("0,00", null, null, null, List.of(""), List.of("FROM"), List.of("50")), null);

    assertThat(panel.direction()).isEqualTo("receive");
    assertThat(panel.netDisplay()).isEqualTo("50,00");
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

  // ── cross-currency readouts (register §3.8a, plan stage 7d.2) ─────────────────

  @Test
  void crossCurrencyDerivesPerLineEquivalentsAndRemainingInEveryCurrency() {
    // CHF card, USD spending (90 total), EUR base (95). Two expense lines 60 + 30 USD.
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, CHF)));

    SplitPanel panel =
        assembler.panel(
            crossForm("90", USD, "100", "95", List.of("expense", "expense"), List.of("60", "30")),
            null);

    SplitCurrency currency = panel.currency();
    assertThat(currency.crossCurrency()).isTrue();
    assertThat(currency.fundingCurrencyCode()).isEqualTo(CHF);
    assertThat(currency.spendingCurrencyCode()).isEqualTo(USD);
    assertThat(currency.baseCurrencyCode()).isEqualTo(EUR);
    assertThat(currency.neitherIsBase()).isTrue();

    // The spending lines are subdivided; each shows its funding (CHF, rate 100/90) and base (EUR,
    // rate 95/90) equivalents read-only.
    assertThat(panel.lines().get(0).accountAmount()).isEqualTo("66,67");
    assertThat(panel.lines().get(0).baseAmount()).isEqualTo("63,33");
    assertThat(panel.lines().get(1).accountAmount()).isEqualTo("33,33");
    assertThat(panel.lines().get(1).baseAmount()).isEqualTo("31,67");

    // All three remainings reach zero together (the lines sum to the spending total).
    assertThat(panel.remaining()).isEqualTo("0,00");
    assertThat(currency.remainingFunding()).isEqualTo("0,00");
    assertThat(currency.remainingBase()).isEqualTo("0,00");
    // The direction cue reads in the funding currency (the amount off the card).
    assertThat(panel.netDisplay()).isEqualTo("100,00");
    assertThat(panel.direction()).isEqualTo("pay");
  }

  @Test
  void crossCurrencyRemainingsStayProportionalWhileUnbalanced() {
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, CHF)));

    // Only 60 of the 90 USD allocated → 30 USD remains, and the funding/base remainings mirror it.
    SplitPanel panel =
        assembler.panel(crossForm("90", USD, "100", "95", List.of("expense"), List.of("60")), null);

    assertThat(panel.remaining()).isEqualTo("30,00");
    assertThat(panel.currency().remainingFunding()).isEqualTo("33,33"); // 100 − 60×100/90
    assertThat(panel.currency().remainingBase()).isEqualTo("31,67"); // 95 − 60×95/90
    assertThat(panel.balanced()).isFalse();
  }
}
