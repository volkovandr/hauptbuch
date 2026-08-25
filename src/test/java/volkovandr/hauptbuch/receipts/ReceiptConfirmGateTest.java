package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.SplitCurrency;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Unit tier (§1.5): the Confirm gate's hard blocks (plan §9g) — the strict rung above 9f's lenient
 * Save. Each test states one thing that must be true before a draft may become postings, and the
 * happy case states that a complete draft passes them all.
 *
 * <p>The cross-currency checks (issue receipts/23, decision 8) are the four things {@code
 * DockSplitService} would otherwise throw raw on. The gate reads the header state the assembler
 * already resolved, so these tests hand it that state directly rather than re-deriving it — the
 * rule itself is {@code SplitCurrencyService}'s and is tested there.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptConfirmGateTest {

  private static final long CASH = 1L;
  private static final long FUEL = 2L;
  private static final long CAR = 3L;
  private static final long CARD = 4L;
  private static final long SAVINGS = 5L;
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String USD = "USD";
  private static final String DATE = "2026-08-03";
  private static final int FRACTION_DIGITS = 2;

  @Mock AccountService accountService;
  @Mock SettingsService settingsService;

  private ReceiptConfirmGate gate;

  @BeforeEach
  void setUp() {
    gate = new ReceiptConfirmGate(accountService, settingsService);
    lenient()
        .when(accountService.findById(CASH))
        .thenReturn(Optional.of(account(CASH, "Cash", EUR)));
    lenient()
        .when(accountService.findById(CARD))
        .thenReturn(Optional.of(account(CARD, "Card", CHF)));
    lenient().when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    // Fuel is a posting leaf; Car has been subdivided and is not offered any more.
    lenient()
        .when(accountService.findPostableLeafPaths(any(), anyString()))
        .thenReturn(List.of(new AccountPath(FUEL, "Car - Fuel")));
  }

  @Test
  void completeDraftPasses() {
    assertThat(problems(form(DATE, CASH, EUR, "42,14", categoryLine("42,14", FUEL)))).isEmpty();
  }

  @Test
  void blocksMissingDate() {
    assertThat(problems(form("", CASH, EUR, "42,14", categoryLine("42,14", FUEL))))
        .anyMatch(p -> p.contains("date"));
  }

  @Test
  void blocksMissingAccount() {
    assertThat(problems(form(DATE, null, EUR, "42,14", categoryLine("42,14", FUEL))))
        .anyMatch(p -> p.contains("paid from"));
  }

  @Test
  void blocksMissingTotal() {
    assertThat(problems(form(DATE, CASH, EUR, "", categoryLine("42,14", FUEL))))
        .anyMatch(p -> p.contains("total"));
  }

  @Test
  void blocksTotalThatTheLinesDoNotAddUpTo() {
    assertThat(problems(form(DATE, CASH, EUR, "50,00", categoryLine("42,14", FUEL))))
        .anyMatch(p -> p.contains("do not add up") && p.contains("7,86"));
  }

  @Test
  void blocksLineWithNoTarget() {
    assertThat(problems(form(DATE, CASH, EUR, "42,14", categoryLine("42,14", null))))
        .anyMatch(p -> p.contains("no category yet") && p.contains("Diesel"));
  }

  @Test
  void blocksCategorySubdividedSinceTheAnalysis() {
    assertThat(problems(form(DATE, CASH, EUR, "42,14", categoryLine("42,14", CAR))))
        .anyMatch(p -> p.contains("split into sub-categories"));
  }

  @Test
  void blocksDraftWithNoLines() {
    assertThat(problems(form(DATE, CASH, EUR, "42,14"))).anyMatch(p -> p.contains("at least one"));
  }

  @Test
  void beneficiaryLineNeedsNoCategory() {
    assertThat(problems(form(DATE, CASH, EUR, "10,00", personLine("10,00", "Max")))).isEmpty();
  }

  // ── cross-currency (issue receipts/23, decision 8) ───────────────────────────

  @Test
  void crossCurrencyDraftWithBothTotalsPasses() {
    assertThat(crossProblems(USD, "40,00", "38,00", categoryLine("42,14", FUEL))).isEmpty();
  }

  @Test
  void blocksCrossCurrencyWithoutTheFundingTotal() {
    assertThat(crossProblems(USD, "", "38,00", categoryLine("42,14", FUEL)))
        .anyMatch(p -> p.contains("came off the account") && p.contains(CHF));
  }

  @Test
  void blocksCrossCurrencyWithoutTheBaseTotalWhenNeitherLegIsBase() {
    assertThat(crossProblems(USD, "40,00", "", categoryLine("42,14", FUEL)))
        .anyMatch(p -> p.contains("Enter the EUR amount") && p.contains("base currency"));
  }

  @Test
  void needsNoBaseTotalWhenTheSpendingCurrencyIsTheBase() {
    // A CHF card paying a EUR receipt: EUR is the base, so the receipt's own total IS the base
    // figure and no third field ever renders.
    assertThat(crossProblems(EUR, "40,00", "", categoryLine("42,14", FUEL))).isEmpty();
  }

  @Test
  void blocksCrossCurrencyWhileTheBookHasNoBaseCurrency() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());

    // With no base set the header falls back to the funding leg, so no third field is asked for —
    // but the split has nothing to balance in, which is what this block says.
    ReceiptEditorForm form = form(DATE, CARD, USD, "42,14", categoryLine("42,14", FUEL));
    SplitCurrency header =
        new SplitCurrency(true, CHF, USD, CHF, false, "40,00", "40,00", "", "", "0", "0");

    assertThat(gate.problems(form, editorFor(form, header)))
        .anyMatch(p -> p.contains("no base currency") && p.contains("Settings"));
  }

  @Test
  void blocksTransferLineTargetingAnAccountInThirdCurrency() {
    when(accountService.findById(SAVINGS))
        .thenReturn(Optional.of(account(SAVINGS, "Savings", CHF)));

    assertThat(crossProblems(USD, "40,00", "38,00", transferLine("42,14", SAVINGS)))
        .anyMatch(p -> p.contains("Savings") && p.contains("must target a USD account"));
  }

  @Test
  void allowsTransferLineTargetingAnAccountInTheReceiptsCurrency() {
    when(accountService.findById(SAVINGS))
        .thenReturn(Optional.of(account(SAVINGS, "Savings", USD)));

    assertThat(crossProblems(USD, "40,00", "38,00", transferLine("42,14", SAVINGS))).isEmpty();
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private List<String> problems(ReceiptEditorForm form) {
    return gate.problems(
        form,
        editorFor(form, new SplitCurrency(false, EUR, EUR, EUR, false, "", "", "", "", "0", "0")));
  }

  /**
   * A CHF card paying a receipt billed in {@code spending}, with the header totals the operator has
   * (or has not) supplied — the state {@code SplitCurrencyService} would resolve for it.
   */
  private List<String> crossProblems(
      String spending, String fundingTotal, String baseTotal, WorkingLine... lines) {
    ReceiptEditorForm form = form(DATE, CARD, spending, "42,14", lines);
    boolean neitherIsBase = !EUR.equals(spending);
    SplitCurrency header =
        new SplitCurrency(
            true,
            CHF,
            spending,
            EUR,
            neitherIsBase,
            fundingTotal,
            neitherIsBase ? baseTotal : "42,14",
            "",
            "",
            "0",
            "0");
    return gate.problems(form, editorFor(form, header));
  }

  /**
   * The readout the assembler would produce for this form — only {@code status}, {@code remaining}
   * and {@code currency} matter to the gate, and the first two follow from total − |Σ lines|
   * exactly as the live readout does.
   */
  private static ReceiptEditor editorFor(ReceiptEditorForm form, SplitCurrency currency) {
    boolean hasTotal = form.total() != null && !form.total().isBlank();
    BigDecimal total = hasTotal ? ReceiptEditorText.parse(form.total()) : BigDecimal.ZERO;
    BigDecimal net = BigDecimal.ZERO;
    for (int i = 0; i < form.lineCount(); i++) {
      net = net.add(ReceiptEditorText.parse(ReceiptEditorForm.at(form.lineAmount(), i)).abs());
    }
    BigDecimal remaining = total.subtract(net);
    boolean balanced = remaining.signum() == 0;
    return new ReceiptEditor(
        null,
        "",
        form.accountId(),
        currency,
        form.total(),
        "",
        "",
        MoneyFormat.number(remaining, FRACTION_DIGITS),
        balanced,
        !hasTotal ? "none" : balanced ? "ok" : "warn",
        List.of());
  }

  private static Account account(long id, String name, String currency) {
    return new Account(id, name, "asset", null, currency, null, null, null, null, false, false);
  }

  private static WorkingLine categoryLine(String amount, Long categoryId) {
    return new WorkingLine(
        "Diesel",
        categoryId == null ? "" : "Car - Fuel",
        categoryId == null ? "" : String.valueOf(categoryId),
        categoryId == null ? "" : "expense",
        "",
        "",
        "",
        "",
        amount,
        "",
        "",
        List.of());
  }

  private static WorkingLine transferLine(String amount, long targetId) {
    return new WorkingLine(
        "Cashback",
        "To → Savings",
        String.valueOf(targetId),
        "",
        "TO",
        "",
        "",
        "",
        amount,
        "",
        "",
        List.of());
  }

  private static WorkingLine personLine(String amount, String person) {
    return new WorkingLine(
        "Max's share", "for " + person, "", "", "", person, "FOR", "", amount, "", "", List.of());
  }

  private static ReceiptEditorForm form(
      String date, Long accountId, String currency, String total, WorkingLine... lines) {
    return WorkingLine.toForm(
        new ReceiptEditorHeader(date, "", accountId, currency, total, "", "", "", ""),
        List.of(lines));
  }
}
