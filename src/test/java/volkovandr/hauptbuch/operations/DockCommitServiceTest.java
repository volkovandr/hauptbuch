package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.ledger.TransactionDraft;

/**
 * Unit tier (plan §1.5): the dock commit path's decision logic with the engine and its
 * collaborators mocked — sign resolution (register §3.8, the load-bearing part), the cross-currency
 * leg-building + base-freeze (register §3.8a, plan stage 7d.1), and the orchestration that turns a
 * dock entry into a balanced pair of legs. The engine's own invariants and the per-currency-leaf
 * routing are proven in their own tests; here we prove the dock assembles the right draft.
 */
@ExtendWith(MockitoExtension.class)
class DockCommitServiceTest {

  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String USD = "USD";
  private static final long CASH_ID = 1L;
  private static final long CATEGORY_ID = 2L;
  private static final long LEAF_ID = 3L;
  private static final LocalDate DATE = LocalDate.of(2026, 2, 1);

  @Mock private AccountService accountService;
  @Mock private PayeeService payeeService;
  @Mock private CurrencyLeafService currencyLeafService;
  @Mock private LedgerService ledgerService;
  @Mock private SettingsService settingsService;

  private DockCommitService dockCommitService;

  @BeforeEach
  void setUp() {
    dockCommitService =
        new DockCommitService(
            accountService, payeeService, currencyLeafService, ledgerService, settingsService);
  }

  private static Account account(long id, String type, String currency) {
    return new Account(id, "n", type, null, currency, null, null, null, null, false);
  }

  private static DockEntry simpleEntry(String amount) {
    return new DockEntry(
        null, DATE, CASH_ID, 42L, null, CATEGORY_ID, null, amount, null, null, "lunch");
  }

  // ── sign resolution (register §3.8) ─────────────────────────────────────────

  @Test
  void expenseCounterpartMakesTheFundingLegAnOutflow() {
    assertThat(DockCommitService.signedFundingAmount("10", EXPENSE)).isEqualByComparingTo("-10");
  }

  @Test
  void incomeCounterpartMakesTheFundingLegAnInflow() {
    assertThat(DockCommitService.signedFundingAmount("2.500,00", INCOME))
        .isEqualByComparingTo("2500");
  }

  @Test
  void explicitPlusOverridesAnExpenseIntoRefundInflow() {
    // A refund is an inflow to an expense category — the override the sign-free scheme can't
    // express
    // without it (register §3.8).
    assertThat(DockCommitService.signedFundingAmount("+10", EXPENSE)).isEqualByComparingTo("10");
  }

  @Test
  void explicitMinusOverridesAnIncomeIntoAnOutflow() {
    assertThat(DockCommitService.signedFundingAmount("-10", INCOME)).isEqualByComparingTo("-10");
  }

  @Test
  void acceptsTheUnicodeMinusAsAnOverride() {
    assertThat(DockCommitService.signedFundingAmount("−10", EXPENSE)).isEqualByComparingTo("-10");
  }

  @Test
  void rejectsBlankAmount() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> DockCommitService.signedFundingAmount("  ", EXPENSE));
  }

  // ── orchestration (single-currency) ──────────────────────────────────────────

  @Test
  void commitAssemblesBalancedPairAndRecordsIt() {
    Account cash = account(CASH_ID, "asset", EUR);
    Account leaf = account(LEAF_ID, EXPENSE, EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, EUR)).thenReturn(leaf);
    when(payeeService.resolvePayee(42L, null)).thenReturn(42L);
    when(ledgerService.recordTransaction(any())).thenReturn(99L);

    long txnId = dockCommitService.commit(simpleEntry("20"));

    assertThat(txnId).isEqualTo(99L);

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    // Cash −20 / Food +20 — a balanced single-currency expense (register §3.8).
    assertThat(legs).hasSize(2);
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-20");
    assertThat(leg(legs, LEAF_ID)).isEqualByComparingTo("20");
    assertThat(baseAmount(legs, CASH_ID)).isNull();
    assertThat(baseAmount(legs, LEAF_ID)).isNull();
    assertThat(draft.getValue().payeeId()).isEqualTo(42L);
    assertThat(draft.getValue().note()).isEqualTo("lunch");
    assertThat(draft.getValue().date()).isEqualTo(DATE);
  }

  @Test
  void commitRoutesTheCategoryToTheFundingAccountsCurrencyWhenNoOverride() {
    // The paying account is CHF, so the category leaf must be resolved in CHF (§6.5) — the dock
    // never
    // hand-picks the currency leaf unless the currency selector is overridden (§3.5).
    Account chfCard = account(CASH_ID, "asset", CHF);
    Account chfLeaf = account(LEAF_ID, EXPENSE, CHF);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(chfCard));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, CHF)).thenReturn(chfLeaf);
    when(payeeService.resolvePayee(null, "Migros - Switzerland")).thenReturn(7L);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    DockEntry entry =
        new DockEntry(
            null,
            DATE,
            CASH_ID,
            null,
            "Migros - Switzerland",
            CATEGORY_ID,
            null,
            "10",
            null,
            null,
            null);
    dockCommitService.commit(entry);

    verify(currencyLeafService).resolveCurrencyLeaf(CATEGORY_ID, CHF);
    verify(payeeService).resolvePayee(null, "Migros - Switzerland");
  }

  @Test
  void commitRejectsAnUnknownFundingAccount() {
    when(accountService.findById(CASH_ID)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockCommitService.commit(simpleEntry("10")))
        .withMessageContaining("No account");
  }

  // ── cross-currency (register §3.5/§3.8a, plan stage 7d.1) ────────────────────

  @Test
  void overridingTheCurrencySelectorRoutesToThatCurrencysLeaf() {
    Account eurCard = account(CASH_ID, "asset", EUR);
    Account chfLeaf = account(LEAF_ID, EXPENSE, CHF);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(eurCard));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, CHF)).thenReturn(chfLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    DockEntry entry =
        new DockEntry(null, DATE, CASH_ID, null, null, CATEGORY_ID, CHF, "9,10", "10", null, null);
    dockCommitService.commit(entry);

    verify(currencyLeafService).resolveCurrencyLeaf(CATEGORY_ID, CHF);
  }

  @Test
  void oneForeignSideFreezesTheBaseLegsOwnAmountAsBothBaseAmounts() {
    // EUR card (the book's base) pays for a CHF-priced item: the EUR leg's base_amount is its own
    // amount; the CHF leg's is the negation — no separate base field, as register §3.8a describes.
    Account eurCard = account(CASH_ID, "asset", EUR);
    Account chfLeaf = account(LEAF_ID, EXPENSE, CHF);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(eurCard));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, CHF)).thenReturn(chfLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    DockEntry entry =
        new DockEntry(null, DATE, CASH_ID, null, null, CATEGORY_ID, CHF, "9,10", "10", null, null);
    dockCommitService.commit(entry);

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();

    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-9.10");
    assertThat(leg(legs, LEAF_ID)).isEqualByComparingTo("10");
    assertThat(baseAmount(legs, CASH_ID)).isEqualByComparingTo("-9.10");
    assertThat(baseAmount(legs, LEAF_ID)).isEqualByComparingTo("9.10");
    assertThat(baseAmount(legs, CASH_ID).add(baseAmount(legs, LEAF_ID)))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void neitherLegBaseUsesTheConfirmedBaseAmountFrozenOnBothLegs() {
    // CHF card pays for a USD-priced item, base is EUR — neither native leg is base, so the
    // confirmed base-amount field is frozen (with opposite signs) on both legs.
    Account chfCard = account(CASH_ID, "asset", CHF);
    Account usdLeaf = account(LEAF_ID, EXPENSE, USD);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(chfCard));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, USD)).thenReturn(usdLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    DockEntry entry =
        new DockEntry(null, DATE, CASH_ID, null, null, CATEGORY_ID, USD, "9", "10", "8,50", null);
    dockCommitService.commit(entry);

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();

    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-9");
    assertThat(leg(legs, LEAF_ID)).isEqualByComparingTo("10");
    assertThat(baseAmount(legs, CASH_ID)).isEqualByComparingTo("-8.50");
    assertThat(baseAmount(legs, LEAF_ID)).isEqualByComparingTo("8.50");
  }

  @Test
  void crossCurrencyRejectsMissingCategoryAmount() {
    Account eurCard = account(CASH_ID, "asset", EUR);
    Account chfLeaf = account(LEAF_ID, EXPENSE, CHF);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(eurCard));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, CHF)).thenReturn(chfLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);

    DockEntry entry =
        new DockEntry(null, DATE, CASH_ID, null, null, CATEGORY_ID, CHF, "9,10", null, null, null);
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockCommitService.commit(entry))
        .withMessageContaining("CHF");
  }

  @Test
  void neitherLegBaseRejectsMissingBaseAmount() {
    Account chfCard = account(CASH_ID, "asset", CHF);
    Account usdLeaf = account(LEAF_ID, EXPENSE, USD);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(chfCard));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, USD)).thenReturn(usdLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    DockEntry entry =
        new DockEntry(null, DATE, CASH_ID, null, null, CATEGORY_ID, USD, "9", "10", null, null);
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockCommitService.commit(entry))
        .withMessageContaining(EUR);
  }

  @Test
  void crossCurrencyWithoutBaseCurrencySetIsRejected() {
    Account eurCard = account(CASH_ID, "asset", EUR);
    Account chfLeaf = account(LEAF_ID, EXPENSE, CHF);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(eurCard));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, CHF)).thenReturn(chfLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());

    DockEntry entry =
        new DockEntry(null, DATE, CASH_ID, null, null, CATEGORY_ID, CHF, "9,10", "10", null, null);
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> dockCommitService.commit(entry));
  }

  // ── edit & void (register §3.1) ───────────────────────────────────────────────

  @Test
  void editReThreadsTheExistingTransactionInsteadOfRecording() {
    Account cash = account(CASH_ID, "asset", EUR);
    Account leaf = account(LEAF_ID, EXPENSE, EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, EUR)).thenReturn(leaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);

    // A non-null transactionId means edit mode: editTransaction is called, recordTransaction is
    // not.
    DockEntry entry =
        new DockEntry(55L, DATE, CASH_ID, null, null, CATEGORY_ID, null, "30", null, null, null);
    long txnId = dockCommitService.commit(entry);

    assertThat(txnId).isEqualTo(55L);
    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).editTransaction(eq(55L), draft.capture());
    verify(ledgerService, never()).recordTransaction(any());
    assertThat(leg(draft.getValue().postings(), CASH_ID)).isEqualByComparingTo("-30");
    assertThat(leg(draft.getValue().postings(), LEAF_ID)).isEqualByComparingTo("30");
  }

  @Test
  void voidTransactionDelegatesToTheEngine() {
    dockCommitService.voidTransaction(7L);
    verify(ledgerService).voidTransaction(7L);
  }

  private static BigDecimal leg(List<PostingDraft> legs, long accountId) {
    return legs.stream().filter(l -> l.accountId() == accountId).findFirst().orElseThrow().amount();
  }

  private static BigDecimal baseAmount(List<PostingDraft> legs, long accountId) {
    return legs.stream()
        .filter(l -> l.accountId() == accountId)
        .findFirst()
        .orElseThrow()
        .baseAmount();
  }
}
