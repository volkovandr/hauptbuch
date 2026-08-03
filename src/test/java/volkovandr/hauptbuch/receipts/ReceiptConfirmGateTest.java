package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

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
import volkovandr.hauptbuch.operations.SplitCurrency;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Unit tier (§1.5): the Confirm gate's hard blocks (plan §9g) — the strict rung above 9f's lenient
 * Save. Each test states one thing that must be true before a draft may become postings, and the
 * happy case states that a complete draft passes them all.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptConfirmGateTest {

  private static final long CASH = 1L;
  private static final long FUEL = 2L;
  private static final long CAR = 3L;
  private static final String EUR = "EUR";
  private static final int FRACTION_DIGITS = 2;

  @Mock AccountService accountService;

  private ReceiptConfirmGate gate;

  @BeforeEach
  void setUp() {
    gate = new ReceiptConfirmGate(accountService);
    lenient()
        .when(accountService.findById(CASH))
        .thenReturn(Optional.of(account(CASH, "Cash", "asset", EUR)));
    // Fuel is a posting leaf; Car has been subdivided and is not offered any more.
    lenient()
        .when(accountService.findPostableLeafPaths(any(), anyString()))
        .thenReturn(List.of(new AccountPath(FUEL, "Car - Fuel")));
  }

  @Test
  void completeDraftPasses() {
    assertThat(problems(form("2026-08-03", CASH, EUR, "42,14", categoryLine("42,14", FUEL))))
        .isEmpty();
  }

  @Test
  void blocksMissingDate() {
    assertThat(problems(form("", CASH, EUR, "42,14", categoryLine("42,14", FUEL))))
        .anyMatch(p -> p.contains("date"));
  }

  @Test
  void blocksMissingAccount() {
    assertThat(problems(form("2026-08-03", null, EUR, "42,14", categoryLine("42,14", FUEL))))
        .anyMatch(p -> p.contains("paid from"));
  }

  @Test
  void blocksCrossCurrencyAsNotImplemented() {
    assertThat(problems(form("2026-08-03", CASH, "CHF", "42,14", categoryLine("42,14", FUEL))))
        .anyMatch(p -> p.contains("cross-currency") && p.contains("Cash"));
  }

  @Test
  void blocksMissingTotal() {
    assertThat(problems(form("2026-08-03", CASH, EUR, "", categoryLine("42,14", FUEL))))
        .anyMatch(p -> p.contains("total"));
  }

  @Test
  void blocksTotalThatTheLinesDoNotAddUpTo() {
    assertThat(problems(form("2026-08-03", CASH, EUR, "50,00", categoryLine("42,14", FUEL))))
        .anyMatch(p -> p.contains("do not add up") && p.contains("7,86"));
  }

  @Test
  void blocksLineWithNoTarget() {
    assertThat(problems(form("2026-08-03", CASH, EUR, "42,14", categoryLine("42,14", null))))
        .anyMatch(p -> p.contains("no category yet") && p.contains("Diesel"));
  }

  @Test
  void blocksCategorySubdividedSinceTheAnalysis() {
    assertThat(problems(form("2026-08-03", CASH, EUR, "42,14", categoryLine("42,14", CAR))))
        .anyMatch(p -> p.contains("split into sub-categories"));
  }

  @Test
  void blocksDraftWithNoLines() {
    assertThat(problems(form("2026-08-03", CASH, EUR, "42,14")))
        .anyMatch(p -> p.contains("at least one line"));
  }

  @Test
  void beneficiaryLineNeedsNoCategory() {
    assertThat(problems(form("2026-08-03", CASH, EUR, "10,00", personLine("10,00", "Max"))))
        .isEmpty();
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private List<String> problems(ReceiptEditorForm form) {
    return gate.problems(form, editorFor(form));
  }

  /**
   * The readout the assembler would produce for this form — only {@code status} and {@code
   * remaining} matter to the gate, and both follow from total − |Σ lines| exactly as the live
   * readout does.
   */
  private static ReceiptEditor editorFor(ReceiptEditorForm form) {
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
        new SplitCurrency(false, EUR, EUR, EUR, false, "", "", "", "", "0", "0"),
        form.total(),
        "",
        "",
        MoneyFormat.number(remaining, FRACTION_DIGITS),
        balanced,
        !hasTotal ? "none" : balanced ? "ok" : "warn",
        false,
        List.of());
  }

  private static Account account(long id, String name, String type, String currency) {
    return new Account(id, name, type, null, currency, null, null, null, null, false, false);
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

  private static WorkingLine personLine(String amount, String person) {
    return new WorkingLine(
        "Max's share", "for " + person, "", "", "", person, "FOR", "", amount, "", "", List.of());
  }

  private static ReceiptEditorForm form(
      String date, Long accountId, String currency, String total, WorkingLine... lines) {
    return WorkingLine.toForm(date, "", accountId, currency, total, "", "", List.of(lines));
  }
}
