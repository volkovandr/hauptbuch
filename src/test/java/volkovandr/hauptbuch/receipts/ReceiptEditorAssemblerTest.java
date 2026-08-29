package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.operations.SplitCurrencyContext;
import volkovandr.hauptbuch.operations.SplitCurrencyQuery;
import volkovandr.hauptbuch.operations.SplitCurrencyService;

/**
 * Unit tier (§1.5): the post-process editor's use of the shared cross-currency header (issue
 * receipts/23) — it asks {@code operations} for the header state and maps that onto the view model,
 * rather than deriving any of it. Each line gains its funding and base equivalents beside the
 * amount the operator still types in the receipt's own currency.
 *
 * <p>The header <em>rule</em> itself belongs to {@code SplitCurrencyServiceTest}; here the service
 * is mocked, which is also what keeps the module boundary honest — {@code receipts} only ever sees
 * its public types.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptEditorAssemblerTest {

  private static final long CARD = 1L;
  private static final String EUR = "EUR";
  private static final String USD = "USD";
  private static final String CHF = "CHF";

  @Mock private AccountService accountService;
  @Mock private LedgerService ledgerService;
  @Mock private SplitCurrencyService splitCurrencyService;

  private ReceiptEditorAssembler assembler;

  @BeforeEach
  void setUp() {
    assembler = new ReceiptEditorAssembler(accountService, ledgerService, splitCurrencyService);
    lenient().when(ledgerService.labelsForTagIds(any())).thenReturn(Map.of());
  }

  @Test
  void asksForTheHeaderStateWithThePayingAccountsCurrencyAsTheFundingLeg() {
    when(accountService.findById(CARD)).thenReturn(Optional.of(account(CHF)));
    when(splitCurrencyService.resolve(any())).thenReturn(cross());

    assembler.panel(form(CARD, USD, "90", "100", "95", line("90")));

    // A receipt's funding leg is always a real own account — never a person sigil, unlike the
    // register's split panel — so the account's own currency is what the header is resolved
    // against.
    verify(splitCurrencyService).resolve(new SplitCurrencyQuery(CHF, USD, "90", "100", "95"));
  }

  @Test
  void fallsBackToTheReceiptsOwnCurrencyUntilAnAccountIsPicked() {
    when(splitCurrencyService.resolve(any())).thenReturn(single());

    assembler.panel(form(null, USD, "90", "", "", line("90")));

    // No paying account yet ⇒ nothing to diverge from, so the header stays single-currency and its
    // chrome never renders.
    verify(splitCurrencyService).resolve(new SplitCurrencyQuery(USD, USD, "90", "", ""));
  }

  @Test
  void crossCurrencyReceiptDerivesEachLinesFundingAndBaseEquivalents() {
    when(accountService.findById(CARD)).thenReturn(Optional.of(account(CHF)));
    when(splitCurrencyService.resolve(any())).thenReturn(cross());

    ReceiptEditor editor =
        assembler.panel(form(CARD, USD, "90", "100", "95", line("60"), line("30")));

    // 60 USD → 60×100/90 CHF and 60×95/90 EUR; likewise for the 30.
    assertThat(editor.lines().get(0).view().accountAmount()).isEqualTo("66,67");
    assertThat(editor.lines().get(0).view().baseAmount()).isEqualTo("63,33");
    assertThat(editor.lines().get(1).view().accountAmount()).isEqualTo("33,33");
    assertThat(editor.lines().get(1).view().baseAmount()).isEqualTo("31,67");
    // Only the read-only companions convert — what the operator types is what the receipt says.
    assertThat(editor.lines().get(0).view().amount()).isEqualTo("60");
  }

  @Test
  void theReadoutCountsRemainingInEveryCurrencyThatIsInPlay() {
    when(accountService.findById(CARD)).thenReturn(Optional.of(account(CHF)));
    when(splitCurrencyService.resolve(any())).thenReturn(cross());

    ReceiptEditor editor = assembler.panel(form(CARD, USD, "90", "100", "95", line("60")));

    // 60 of the 90 USD allocated; the funding and base remainings mirror it through the same rate.
    assertThat(editor.remaining()).isEqualTo("30,00");
    assertThat(editor.currency().remainingFunding()).isEqualTo("33,33");
    assertThat(editor.currency().remainingBase()).isEqualTo("31,67");
    assertThat(editor.balanced()).isFalse();
  }

  @Test
  void sameCurrencyReceiptRendersNoDerivedAmounts() {
    when(accountService.findById(CARD)).thenReturn(Optional.of(account(EUR)));
    when(splitCurrencyService.resolve(any())).thenReturn(single());

    ReceiptEditor editor = assembler.panel(form(CARD, EUR, "42,14", "", "", line("42,14")));

    assertThat(editor.currency().crossCurrency()).isFalse();
    assertThat(editor.lines().get(0).view().accountAmount()).isEmpty();
    assertThat(editor.lines().get(0).view().baseAmount()).isEmpty();
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** A CHF card paying a 90 USD receipt: 100 CHF off the card, 95 EUR base. */
  private static SplitCurrencyContext cross() {
    return new SplitCurrencyContext(
        true,
        CHF,
        USD,
        EUR,
        true,
        new BigDecimal("100"),
        new BigDecimal("95"),
        "100",
        "95",
        new BigDecimal("100").divide(new BigDecimal("90"), 10, java.math.RoundingMode.HALF_UP),
        new BigDecimal("95").divide(new BigDecimal("90"), 10, java.math.RoundingMode.HALF_UP));
  }

  private static SplitCurrencyContext single() {
    return new SplitCurrencyContext(
        false,
        EUR,
        EUR,
        EUR,
        false,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "",
        "",
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static Account account(String currency) {
    return new Account(
        CARD, "Card", "asset", null, currency, null, null, null, null, false, false, false);
  }

  private static WorkingLine line(String amount) {
    return new WorkingLine(
        "Diesel", "Car - Fuel", "7", "expense", "", "", "", "", amount, "", "", List.of());
  }

  private static ReceiptEditorForm form(
      Long accountId,
      String currency,
      String total,
      String fundingTotal,
      String baseTotal,
      WorkingLine... lines) {
    return WorkingLine.toForm(
        new ReceiptEditorHeader(
            "2026-08-03", "", accountId, currency, total, fundingTotal, baseTotal, "", ""),
        List.of(lines));
  }
}
