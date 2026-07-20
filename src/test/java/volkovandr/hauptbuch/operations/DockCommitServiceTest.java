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
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.debts.PersonTarget;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.ledger.TransactionDraft;
import volkovandr.hauptbuch.ledger.TransferTarget;

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
  private static final long VISA_ID = 4L;
  private static final String TO = TransferTarget.Direction.TO.name();
  private static final String FROM = TransferTarget.Direction.FROM.name();
  private static final String FOR = PersonTarget.Direction.FOR.name();
  private static final String BY = PersonTarget.Direction.BY.name();
  private static final LocalDate DATE = LocalDate.of(2026, 2, 1);

  @Mock private AccountService accountService;
  @Mock private PayeeService payeeService;
  @Mock private CurrencyLeafService currencyLeafService;
  @Mock private LedgerService ledgerService;
  @Mock private SettingsService settingsService;
  @Mock private PersonProvisioningService personProvisioningService;

  private DockCommitService dockCommitService;

  @BeforeEach
  void setUp() {
    dockCommitService =
        new DockCommitService(
            accountService,
            payeeService,
            currencyLeafService,
            ledgerService,
            settingsService,
            personProvisioningService);
  }

  private static Account account(long id, String type, String currency) {
    return new Account(id, "n", type, null, currency, null, null, null, null, false, false);
  }

  private static DockEntry simpleEntry(String amount) {
    return new DockEntry(
        null,
        DATE,
        CASH_ID,
        42L,
        null,
        CATEGORY_ID,
        null,
        amount,
        null,
        null,
        "lunch",
        null,
        null,
        null,
        null,
        List.of());
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
  void attachesTagsToEveryLegOfSimpleTransaction() {
    Account cash = account(CASH_ID, "asset", EUR);
    Account leaf = account(LEAF_ID, EXPENSE, EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, EUR)).thenReturn(leaf);
    when(payeeService.resolvePayee(42L, null)).thenReturn(42L);
    when(ledgerService.recordTransaction(any())).thenReturn(99L);

    // A doubly-picked chip (5 twice) must de-dupe so the posting_tag unique constraint can't fire.
    DockEntry entry =
        new DockEntry(
            null,
            DATE,
            CASH_ID,
            42L,
            null,
            CATEGORY_ID,
            null,
            "20",
            null,
            null,
            "lunch",
            null,
            null,
            null,
            null,
            List.of(5L, 6L, 5L));
    dockCommitService.commit(entry);

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    // A transaction-level tag lands on every leg (data-model §10.2, owner decision 2026-07-14).
    assertThat(tagIds(legs, CASH_ID)).containsExactly(5L, 6L);
    assertThat(tagIds(legs, LEAF_ID)).containsExactly(5L, 6L);
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
            null,
            null,
            null,
            null,
            null,
            List.of());
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
        new DockEntry(
            null,
            DATE,
            CASH_ID,
            null,
            null,
            CATEGORY_ID,
            CHF,
            "9,10",
            "10",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of());
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
        new DockEntry(
            null,
            DATE,
            CASH_ID,
            null,
            null,
            CATEGORY_ID,
            CHF,
            "9,10",
            "10",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of());
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
        new DockEntry(
            null,
            DATE,
            CASH_ID,
            null,
            null,
            CATEGORY_ID,
            USD,
            "9",
            "10",
            "8,50",
            null,
            null,
            null,
            null,
            null,
            List.of());
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
        new DockEntry(
            null,
            DATE,
            CASH_ID,
            null,
            null,
            CATEGORY_ID,
            CHF,
            "9,10",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of());
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
        new DockEntry(
            null,
            DATE,
            CASH_ID,
            null,
            null,
            CATEGORY_ID,
            USD,
            "9",
            "10",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of());
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
        new DockEntry(
            null,
            DATE,
            CASH_ID,
            null,
            null,
            CATEGORY_ID,
            CHF,
            "9,10",
            "10",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of());
    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> dockCommitService.commit(entry));
  }

  // ── transfers (register §3.5/§3.8, plan stage 7d.3) ──────────────────────────

  private static DockEntry transferEntry(
      String amount, String direction, String counterpartAmount, String baseAmount) {
    return new DockEntry(
        null,
        DATE,
        CASH_ID,
        null,
        null,
        VISA_ID,
        null,
        amount,
        counterpartAmount,
        baseAmount,
        null,
        direction,
        null,
        null,
        null,
        List.of());
  }

  @Test
  void transferToAnotherAccountIsAnOutflowFromTheFundingLeg() {
    Account cash = account(CASH_ID, "asset", EUR);
    Account visa = account(VISA_ID, "liability", EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(accountService.findById(VISA_ID)).thenReturn(Optional.of(visa));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockCommitService.commit(transferEntry("20", TO, null, null));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    // Cash −20 / Visa +20 — a same-currency transfer routes the counter-leg to the real account,
    // not a category leaf (register §3.8). The currency-leaf router is never consulted.
    assertThat(legs).hasSize(2);
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-20");
    assertThat(leg(legs, VISA_ID)).isEqualByComparingTo("20");
    assertThat(baseAmount(legs, CASH_ID)).isNull();
    verify(currencyLeafService, never()).resolveCurrencyLeaf(any(Long.class), any());
  }

  @Test
  void transferFromAnotherAccountIsAnInflowToTheFundingLeg() {
    Account cash = account(CASH_ID, "asset", EUR);
    Account visa = account(VISA_ID, "asset", EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(accountService.findById(VISA_ID)).thenReturn(Optional.of(visa));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockCommitService.commit(transferEntry("20", FROM, null, null));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    // From ← Visa: funds enter the funding account (Cash +20 / Visa −20).
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("20");
    assertThat(leg(legs, VISA_ID)).isEqualByComparingTo("-20");
  }

  @Test
  void crossCurrencyTransferBalancesInBaseFromTheEnteredLegs() {
    // EUR (base) Cash → CHF Visa: one foreign side, so the funding leg's own amount is both legs'
    // base_amount (register §3.8a) — no separate base field. Cash −20 (base −20), Visa +25 CHF
    // (base +20).
    Account cash = account(CASH_ID, "asset", EUR);
    Account visa = account(VISA_ID, "liability", CHF);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(accountService.findById(VISA_ID)).thenReturn(Optional.of(visa));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockCommitService.commit(transferEntry("20", TO, "25", null));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-20");
    assertThat(leg(legs, VISA_ID)).isEqualByComparingTo("25");
    assertThat(baseAmount(legs, CASH_ID)).isEqualByComparingTo("-20");
    assertThat(baseAmount(legs, VISA_ID)).isEqualByComparingTo("20");
  }

  @Test
  void transferToTheSameAccountIsRejected() {
    Account cash = account(CASH_ID, "asset", EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);

    DockEntry entry =
        new DockEntry(
            null, DATE, CASH_ID, null, null, CASH_ID, null, "20", null, null, null, TO, null, null,
            null, List.of());
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockCommitService.commit(entry))
        .withMessageContaining("two different accounts");
  }

  @Test
  void transferToAnUnknownCounterpartAccountIsRejected() {
    Account cash = account(CASH_ID, "asset", EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(accountService.findById(VISA_ID)).thenReturn(Optional.empty());
    when(payeeService.resolvePayee(null, null)).thenReturn(null);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockCommitService.commit(transferEntry("20", TO, null, null)))
        .withMessageContaining("No account");
  }

  // ── persons (for/by), single line (plan stage 8b, register §3.5, data-model §7) ──

  private static DockEntry personEntry(String amount, String direction, String revive) {
    return new DockEntry(
        null, DATE, CASH_ID, null, null, 0L, null, amount, null, null, null, null, "Max", direction,
        revive, List.of());
  }

  @Test
  void forDirectionIsAnOutflowFromTheFundingLeg() {
    Account cash = account(CASH_ID, "asset", EUR);
    Account maxLeaf = account(LEAF_ID, "asset", EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(personProvisioningService.ensureLeaf("Max", EUR, false)).thenReturn(maxLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockCommitService.commit(personEntry("20", FOR, null));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    // "for Max" — you funded it: Cash −20, Max +20 (the leg auto-provisioned at commit).
    assertThat(legs).hasSize(2);
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-20");
    assertThat(leg(legs, LEAF_ID)).isEqualByComparingTo("20");
    verify(currencyLeafService, never()).resolveCurrencyLeaf(any(Long.class), any());
  }

  @Test
  void byDirectionIsAnInflowToTheFundingLeg() {
    Account cash = account(CASH_ID, "asset", EUR);
    Account maxLeaf = account(LEAF_ID, "asset", EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(personProvisioningService.ensureLeaf("Max", EUR, false)).thenReturn(maxLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockCommitService.commit(personEntry("20", BY, null));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    // "by Max" — they funded it: Cash +20, Max −20.
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("20");
    assertThat(leg(legs, LEAF_ID)).isEqualByComparingTo("-20");
  }

  @Test
  void personEntryRoutesToTheFundingAccountsCurrencyWhenNoOverride() {
    Account chfCard = account(CASH_ID, "asset", CHF);
    Account maxLeaf = account(LEAF_ID, "asset", CHF);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(chfCard));
    when(personProvisioningService.ensureLeaf("Max", CHF, false)).thenReturn(maxLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockCommitService.commit(personEntry("20", FOR, null));

    verify(personProvisioningService).ensureLeaf("Max", CHF, false);
  }

  @Test
  void personEntryPassesTheReviveDecisionThrough() {
    Account cash = account(CASH_ID, "asset", EUR);
    Account maxLeaf = account(LEAF_ID, "asset", EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(personProvisioningService.ensureLeaf("Max", EUR, true)).thenReturn(maxLeaf);
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockCommitService.commit(personEntry("20", FOR, "true"));

    verify(personProvisioningService).ensureLeaf("Max", EUR, true);
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
        new DockEntry(
            55L,
            DATE,
            CASH_ID,
            null,
            null,
            CATEGORY_ID,
            null,
            "30",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of());
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

  private static List<Long> tagIds(List<PostingDraft> legs, long accountId) {
    return legs.stream().filter(l -> l.accountId() == accountId).findFirst().orElseThrow().tagIds();
  }
}
