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
 * Unit tier (plan §1.5): the split commit path's decision logic with the engine and its
 * collaborators mocked. The load-bearing part is the mixed-split sign rule (register §3.8, ratified
 * 2026-07-09): each line's <em>signed contribution</em> is {@code +amount} for an income category
 * and {@code −amount} for an expense one (the typed amount already signed, so a storno flows
 * through); the funding leg is the sum of contributions, each category leg is its negated
 * contribution, and the whole set sums to zero by construction. The engine's own invariants and the
 * per-currency-leaf routing are proven elsewhere; here we prove the panel assembles the right
 * draft.
 */
@ExtendWith(MockitoExtension.class)
class DockSplitServiceTest {

  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String USD = "USD";
  private static final long CASH_ID = 1L;
  private static final long CARD_ID = 2L;
  private static final long FOOD_ID = 10L;
  private static final long FOOD_LEAF_ID = 11L;
  private static final long DEPOSIT_ID = 20L;
  private static final long DEPOSIT_LEAF_ID = 21L;
  private static final long SAVINGS_ID = 30L;

  @Mock private AccountService accountService;
  @Mock private PayeeService payeeService;
  @Mock private CurrencyLeafService currencyLeafService;
  @Mock private LedgerService ledgerService;
  @Mock private SettingsService settingsService;

  private DockSplitService dockSplitService;

  @BeforeEach
  void setUp() {
    dockSplitService =
        new DockSplitService(
            accountService, payeeService, currencyLeafService, ledgerService, settingsService);
  }

  private static Account account(long id, String type, String currency) {
    return new Account(id, "n", type, null, currency, null, null, null, null, false, false);
  }

  private void cashFunds() {
    when(accountService.findById(CASH_ID))
        .thenReturn(java.util.Optional.of(account(CASH_ID, "asset", EUR)));
  }

  private void foodLeaf() {
    when(currencyLeafService.resolveCurrencyLeaf(FOOD_ID, EUR))
        .thenReturn(account(FOOD_LEAF_ID, EXPENSE, EUR));
  }

  private void depositLeaf() {
    when(currencyLeafService.resolveCurrencyLeaf(DEPOSIT_ID, EUR))
        .thenReturn(account(DEPOSIT_LEAF_ID, INCOME, EUR));
  }

  private static SplitLineDraft line(long categoryId, String amount) {
    return new SplitLineDraft(categoryId, amount, null, null, List.of());
  }

  private static SplitLineDraft line(long categoryId, String amount, List<Long> tagIds) {
    return new SplitLineDraft(categoryId, amount, null, null, tagIds);
  }

  private static SplitLineDraft transferLine(long accountId, String amount, String direction) {
    return new SplitLineDraft(accountId, amount, null, direction, List.of());
  }

  /** A same-currency split entry (the 7c.2 shape) — the 7d.2 header currency fields left blank. */
  private static SplitEntry entry(
      Long txnId, LocalDate date, long accountId, String note, List<SplitLineDraft> lines) {
    return new SplitEntry(
        txnId, date, accountId, null, null, note, null, null, null, List.of(), lines);
  }

  /** A same-currency split entry carrying transaction-level (funding-leg) tags. */
  private static SplitEntry taggedEntry(
      long accountId, List<Long> tagIds, List<SplitLineDraft> lines) {
    return new SplitEntry(
        null,
        LocalDate.of(2026, 2, 1),
        accountId,
        null,
        null,
        null,
        null,
        null,
        null,
        tagIds,
        lines);
  }

  // The signed-contribution / transfer-contribution sign math lives in SplitLineAmounts (extracted
  // from this service); its unit tests are in SplitLineAmountsTest.

  // ── commit orchestration ───────────────────────────────────────────────────────

  @Test
  void mixedExpenseAndIncomeNetsToaFundingCredit() {
    // Food €20 (expense) + bottle-deposit €3 return (income), paid cash: Cash −17, Food +20,
    // Deposit −3 — the ratified example (2026-07-09). Sum = 0.
    cashFunds();
    foodLeaf();
    depositLeaf();
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(99L);

    SplitEntry entry =
        entry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            "groceries",
            List.of(line(FOOD_ID, "20"), line(DEPOSIT_ID, "3")));
    long txnId = dockSplitService.commit(entry);

    assertThat(txnId).isEqualTo(99L);
    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(legs).hasSize(3);
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-17");
    assertThat(leg(legs, FOOD_LEAF_ID)).isEqualByComparingTo("20");
    assertThat(leg(legs, DEPOSIT_LEAF_ID)).isEqualByComparingTo("-3");
    assertThat(sum(legs)).isEqualByComparingTo("0");
    assertThat(draft.getValue().note()).isEqualTo("groceries");
  }

  @Test
  void fundingLegCarriesTransactionLevelTagsAndEachLineCarriesItsOwn() {
    // The funding leg gets the header (transaction-level) tags; each category leg gets only its own
    // line's chips (register §3.6, plan stage 7e.3, owner decision 2026-07-14). A doubly-picked
    // chip
    // de-dupes so the posting_tag unique constraint can never fire.
    cashFunds();
    foodLeaf();
    depositLeaf();
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockSplitService.commit(
        taggedEntry(
            CASH_ID,
            List.of(7L, 8L, 7L),
            List.of(line(FOOD_ID, "20", List.of(9L)), line(DEPOSIT_ID, "3", List.of()))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(tagIds(legs, CASH_ID)).containsExactly(7L, 8L);
    assertThat(tagIds(legs, FOOD_LEAF_ID)).containsExactly(9L);
    assertThat(tagIds(legs, DEPOSIT_LEAF_ID)).isEmpty();
  }

  @Test
  void purelyExpenseLinesFundAsanOutflow() {
    cashFunds();
    foodLeaf();
    when(currencyLeafService.resolveCurrencyLeaf(DEPOSIT_ID, EUR))
        .thenReturn(account(DEPOSIT_LEAF_ID, EXPENSE, EUR));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockSplitService.commit(
        entry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            List.of(line(FOOD_ID, "15"), line(DEPOSIT_ID, "5"))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-20");
    assertThat(leg(legs, FOOD_LEAF_ID)).isEqualByComparingTo("15");
    assertThat(leg(legs, DEPOSIT_LEAF_ID)).isEqualByComparingTo("5");
  }

  @Test
  void netZeroReceiptRecordsWithaZeroFundingLegOnTheDebitSide() {
    // Return five bottles (income 5), take one Cola (expense 5), pay nothing: the funding leg is
    // exactly zero — a legal, recordable transaction the owner wants kept (2026-07-09).
    cashFunds();
    when(currencyLeafService.resolveCurrencyLeaf(FOOD_ID, EUR))
        .thenReturn(account(FOOD_LEAF_ID, EXPENSE, EUR));
    depositLeaf();
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(2L);

    dockSplitService.commit(
        entry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            List.of(line(FOOD_ID, "5"), line(DEPOSIT_ID, "5"))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("0");
    assertThat(sum(legs)).isEqualByComparingTo("0");
  }

  @Test
  void carriesTheLineNoteOntoItsCategoryLeg() {
    cashFunds();
    foodLeaf();
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockSplitService.commit(
        entry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            List.of(new SplitLineDraft(FOOD_ID, "20", "organic aisle", null, List.of()))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    PostingDraft foodLeg =
        draft.getValue().postings().stream()
            .filter(l -> l.accountId() == FOOD_LEAF_ID)
            .findFirst()
            .orElseThrow();
    assertThat(foodLeg.note()).isEqualTo("organic aisle");
  }

  @Test
  void editReThreadsTheExistingTransactionInsteadOfRecording() {
    cashFunds();
    foodLeaf();
    depositLeaf();
    when(payeeService.resolvePayee(null, null)).thenReturn(null);

    long txnId =
        dockSplitService.commit(
            entry(
                55L,
                LocalDate.of(2026, 2, 1),
                CASH_ID,
                null,
                List.of(line(FOOD_ID, "20"), line(DEPOSIT_ID, "3"))));

    assertThat(txnId).isEqualTo(55L);
    verify(ledgerService).editTransaction(eq(55L), any());
    verify(ledgerService, never()).recordTransaction(any());
  }

  @Test
  void rejectsanEmptyLineList() {
    SplitEntry entry = entry(null, LocalDate.of(2026, 2, 1), CASH_ID, null, List.of());
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockSplitService.commit(entry));
  }

  @Test
  void rejectsanUnknownFundingAccount() {
    when(accountService.findById(CASH_ID)).thenReturn(java.util.Optional.empty());
    SplitEntry entry =
        entry(null, LocalDate.of(2026, 2, 1), CASH_ID, null, List.of(line(FOOD_ID, "20")));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockSplitService.commit(entry))
        .withMessageContaining("No account");
  }

  // ── split transfers (register §3.8, plan stage 7d.3) ───────────────────────────

  @Test
  void splitWithaToTransferLineRoutesTheCounterLegToTheRealAccount() {
    // Cash pays €20 Food and moves €50 to Savings: Cash −70, Food +20, Savings +50. Sum = 0. The
    // transfer leg hits the real own account, signed by direction (TO = outflow), not a leaf.
    cashFunds();
    foodLeaf();
    when(accountService.findById(SAVINGS_ID))
        .thenReturn(java.util.Optional.of(account(SAVINGS_ID, "asset", EUR)));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(3L);

    dockSplitService.commit(
        entry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            List.of(line(FOOD_ID, "20"), transferLine(SAVINGS_ID, "50", "TO"))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(legs).hasSize(3);
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-70");
    assertThat(leg(legs, FOOD_LEAF_ID)).isEqualByComparingTo("20");
    assertThat(leg(legs, SAVINGS_ID)).isEqualByComparingTo("50");
    assertThat(sum(legs)).isEqualByComparingTo("0");
    // A transfer leg is a real own account, never routed through the currency-leaf resolver.
    verify(currencyLeafService, never()).resolveCurrencyLeaf(eq(SAVINGS_ID), any());
  }

  @Test
  void splitWithaFromTransferLineCreditsTheCounterAccount() {
    // Cash pays €20 Food but pulls €50 in from Savings: Cash +30, Food +20, Savings −50. Sum = 0.
    cashFunds();
    foodLeaf();
    when(accountService.findById(SAVINGS_ID))
        .thenReturn(java.util.Optional.of(account(SAVINGS_ID, "asset", EUR)));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(4L);

    dockSplitService.commit(
        entry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            List.of(line(FOOD_ID, "20"), transferLine(SAVINGS_ID, "50", "FROM"))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("30");
    assertThat(leg(legs, FOOD_LEAF_ID)).isEqualByComparingTo("20");
    assertThat(leg(legs, SAVINGS_ID)).isEqualByComparingTo("-50");
    assertThat(sum(legs)).isEqualByComparingTo("0");
  }

  @Test
  void splitTransferToTheFundingAccountItselfIsRejected() {
    cashFunds();
    SplitEntry entry =
        entry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            List.of(transferLine(CASH_ID, "50", "TO")));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockSplitService.commit(entry))
        .withMessageContaining("two different accounts");
  }

  @Test
  void splitTransferToaDifferentlyDenominatedAccountIsRejected() {
    // A same-currency (EUR) split cannot absorb a CHF transfer leg: that is a third currency the
    // header's single shared rate can't express (register §3.8a) — refused with a clear message.
    cashFunds();
    foodLeaf();
    when(accountService.findById(SAVINGS_ID))
        .thenReturn(java.util.Optional.of(account(SAVINGS_ID, "asset", CHF)));
    SplitEntry entry =
        entry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            List.of(line(FOOD_ID, "20"), transferLine(SAVINGS_ID, "50", "TO")));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockSplitService.commit(entry))
        .withMessageContaining("EUR account");
  }

  @Test
  void crossCurrencySplitWithaTransferLineFreezesItsBaseLikeaCategoryLeg() {
    // CHF card, USD receipt (90), EUR base (95). Line 1: Food 60 USD. Line 2: transfer 30 USD to a
    // USD wallet. The transfer leg is in the spending currency with a derived base, exactly like a
    // category leg; the funding leg stays pinned to the header totals and Σ base_amount = 0.
    when(accountService.findById(CARD_ID))
        .thenReturn(java.util.Optional.of(account(CARD_ID, "asset", CHF)));
    when(accountService.findById(SAVINGS_ID))
        .thenReturn(java.util.Optional.of(account(SAVINGS_ID, "asset", USD)));
    when(settingsService.baseCurrency()).thenReturn(java.util.Optional.of(EUR));
    when(currencyLeafService.resolveCurrencyLeaf(FOOD_ID, USD))
        .thenReturn(account(FOOD_LEAF_ID, EXPENSE, USD));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(11L);

    dockSplitService.commit(
        crossEntry(
            USD, "100", "95", List.of(line(FOOD_ID, "60"), transferLine(SAVINGS_ID, "30", "TO"))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(legs).hasSize(3);
    assertThat(leg(legs, CARD_ID)).isEqualByComparingTo("-100");
    assertThat(base(legs, CARD_ID)).isEqualByComparingTo("-95");
    assertThat(leg(legs, FOOD_LEAF_ID)).isEqualByComparingTo("60");
    assertThat(base(legs, FOOD_LEAF_ID)).isEqualByComparingTo("63.33");
    assertThat(leg(legs, SAVINGS_ID)).isEqualByComparingTo("30");
    assertThat(base(legs, SAVINGS_ID)).isEqualByComparingTo("31.67");
    assertThat(baseSum(legs)).isEqualByComparingTo("0");
  }

  // ── cross-currency split (register §3.8a, plan stage 7d.2, owner-decided 2026-07-13) ───────────

  private static SplitEntry crossEntry(
      String spendingCurrency, String fundingTotal, String baseTotal, List<SplitLineDraft> lines) {
    return new SplitEntry(
        null,
        LocalDate.of(2026, 2, 1),
        CARD_ID,
        null,
        null,
        null,
        spendingCurrency,
        fundingTotal,
        baseTotal,
        List.of(),
        lines);
  }

  @Test
  void crossCurrencySplitPinsFundingToHeaderTotalsAndBalancesInBase() {
    // The owner's worked example: CHF card, USD receipt (90), EUR base. Header 100 CHF off the
    // card, 95 EUR base; two expense lines 60 + 30 USD. Funding is pinned to the header totals and
    // the category legs' base amounts sum to the base total, the last line closing the residual.
    when(accountService.findById(CARD_ID))
        .thenReturn(java.util.Optional.of(account(CARD_ID, "asset", CHF)));
    when(settingsService.baseCurrency()).thenReturn(java.util.Optional.of(EUR));
    when(currencyLeafService.resolveCurrencyLeaf(FOOD_ID, USD))
        .thenReturn(account(FOOD_LEAF_ID, EXPENSE, USD));
    when(currencyLeafService.resolveCurrencyLeaf(DEPOSIT_ID, USD))
        .thenReturn(account(DEPOSIT_LEAF_ID, EXPENSE, USD));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(7L);

    dockSplitService.commit(
        crossEntry(USD, "100", "95", List.of(line(FOOD_ID, "60"), line(DEPOSIT_ID, "30"))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(legs).hasSize(3);
    // Funding pinned to the header totals: −100 CHF native, −95 EUR base (an outflow).
    assertThat(leg(legs, CARD_ID)).isEqualByComparingTo("-100");
    assertThat(base(legs, CARD_ID)).isEqualByComparingTo("-95");
    // Category legs in USD; base derived proportionally, the last line absorbing the residual.
    assertThat(leg(legs, FOOD_LEAF_ID)).isEqualByComparingTo("60");
    assertThat(base(legs, FOOD_LEAF_ID)).isEqualByComparingTo("63.33");
    assertThat(leg(legs, DEPOSIT_LEAF_ID)).isEqualByComparingTo("30");
    assertThat(base(legs, DEPOSIT_LEAF_ID)).isEqualByComparingTo("31.67");
    assertThat(baseSum(legs)).isEqualByComparingTo("0");
  }

  @Test
  void crossCurrencySplitWhereFundingIsBaseNeedsNoSeparateBaseTotal() {
    // EUR card (base), USD spending — two fields, the funding leg's base equals its own amount.
    when(accountService.findById(CARD_ID))
        .thenReturn(java.util.Optional.of(account(CARD_ID, "asset", EUR)));
    when(settingsService.baseCurrency()).thenReturn(java.util.Optional.of(EUR));
    when(currencyLeafService.resolveCurrencyLeaf(FOOD_ID, USD))
        .thenReturn(account(FOOD_LEAF_ID, EXPENSE, USD));
    when(currencyLeafService.resolveCurrencyLeaf(DEPOSIT_ID, USD))
        .thenReturn(account(DEPOSIT_LEAF_ID, EXPENSE, USD));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(8L);

    dockSplitService.commit(
        crossEntry(USD, "95", null, List.of(line(FOOD_ID, "60"), line(DEPOSIT_ID, "30"))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(leg(legs, CARD_ID)).isEqualByComparingTo("-95");
    assertThat(base(legs, CARD_ID)).isEqualByComparingTo("-95");
    assertThat(baseSum(legs)).isEqualByComparingTo("0");
  }

  @Test
  void crossCurrencySplitWhereSpendingIsBaseDerivesBaseFromTheLines() {
    // CHF card, EUR spending (base) — the lines are already in base, so each leg's base equals its
    // own amount and the base total is their sum; no separate base field.
    when(accountService.findById(CARD_ID))
        .thenReturn(java.util.Optional.of(account(CARD_ID, "asset", CHF)));
    when(settingsService.baseCurrency()).thenReturn(java.util.Optional.of(EUR));
    when(currencyLeafService.resolveCurrencyLeaf(FOOD_ID, EUR))
        .thenReturn(account(FOOD_LEAF_ID, EXPENSE, EUR));
    when(currencyLeafService.resolveCurrencyLeaf(DEPOSIT_ID, EUR))
        .thenReturn(account(DEPOSIT_LEAF_ID, EXPENSE, EUR));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(9L);

    dockSplitService.commit(
        crossEntry(EUR, "100", null, List.of(line(FOOD_ID, "60"), line(DEPOSIT_ID, "35"))));

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    assertThat(leg(legs, CARD_ID)).isEqualByComparingTo("-100"); // native off the CHF card
    assertThat(base(legs, CARD_ID)).isEqualByComparingTo("-95"); // base = summed EUR lines
    assertThat(base(legs, FOOD_LEAF_ID)).isEqualByComparingTo("60");
    assertThat(base(legs, DEPOSIT_LEAF_ID)).isEqualByComparingTo("35");
    assertThat(baseSum(legs)).isEqualByComparingTo("0");
  }

  @Test
  void crossCurrencySplitRejectsaBlankFundingTotal() {
    when(accountService.findById(CARD_ID))
        .thenReturn(java.util.Optional.of(account(CARD_ID, "asset", CHF)));
    when(settingsService.baseCurrency()).thenReturn(java.util.Optional.of(EUR));
    when(currencyLeafService.resolveCurrencyLeaf(FOOD_ID, USD))
        .thenReturn(account(FOOD_LEAF_ID, EXPENSE, USD));
    SplitEntry entry = crossEntry(USD, "  ", "95", List.of(line(FOOD_ID, "60")));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockSplitService.commit(entry))
        .withMessageContaining("total is required");
  }

  private static BigDecimal leg(List<PostingDraft> legs, long accountId) {
    return legs.stream().filter(l -> l.accountId() == accountId).findFirst().orElseThrow().amount();
  }

  private static List<Long> tagIds(List<PostingDraft> legs, long accountId) {
    return legs.stream().filter(l -> l.accountId() == accountId).findFirst().orElseThrow().tagIds();
  }

  private static BigDecimal base(List<PostingDraft> legs, long accountId) {
    return legs.stream()
        .filter(l -> l.accountId() == accountId)
        .findFirst()
        .orElseThrow()
        .baseAmount();
  }

  private static BigDecimal sum(List<PostingDraft> legs) {
    return legs.stream().map(PostingDraft::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal baseSum(List<PostingDraft> legs) {
    return legs.stream().map(PostingDraft::baseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
