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
  private static final long CASH_ID = 1L;
  private static final long FOOD_ID = 10L;
  private static final long FOOD_LEAF_ID = 11L;
  private static final long DEPOSIT_ID = 20L;
  private static final long DEPOSIT_LEAF_ID = 21L;

  @Mock private AccountService accountService;
  @Mock private PayeeService payeeService;
  @Mock private CurrencyLeafService currencyLeafService;
  @Mock private LedgerService ledgerService;

  private DockSplitService dockSplitService;

  @BeforeEach
  void setUp() {
    dockSplitService =
        new DockSplitService(accountService, payeeService, currencyLeafService, ledgerService);
  }

  private static Account account(long id, String type, String currency) {
    return new Account(id, "n", type, null, currency, null, null, null, null, false);
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
    return new SplitLineDraft(categoryId, amount, null);
  }

  // ── signed contribution (register §3.8, mixed-split rule) ──────────────────────

  @Test
  void expenseLineContributesNegatively() {
    assertThat(DockSplitService.signedContribution("20", EXPENSE)).isEqualByComparingTo("-20");
  }

  @Test
  void incomeLineContributesPositively() {
    assertThat(DockSplitService.signedContribution("3", INCOME)).isEqualByComparingTo("3");
  }

  @Test
  void stornoOnExpenseLineCountsPositively() {
    // A negative amount on an expense line reverses the spend — it counts as an inflow (§3.8).
    assertThat(DockSplitService.signedContribution("-5", EXPENSE)).isEqualByComparingTo("5");
  }

  @Test
  void stornoOnIncomeLineCountsNegatively() {
    assertThat(DockSplitService.signedContribution("−3", INCOME)).isEqualByComparingTo("-3");
  }

  @Test
  void rejectsBlankLineAmount() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> DockSplitService.signedContribution("  ", EXPENSE));
  }

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
        new SplitEntry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            null,
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
  void purelyExpenseLinesFundAsanOutflow() {
    cashFunds();
    foodLeaf();
    when(currencyLeafService.resolveCurrencyLeaf(DEPOSIT_ID, EUR))
        .thenReturn(account(DEPOSIT_LEAF_ID, EXPENSE, EUR));
    when(payeeService.resolvePayee(null, null)).thenReturn(null);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    dockSplitService.commit(
        new SplitEntry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            null,
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
        new SplitEntry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            null,
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
        new SplitEntry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            null,
            null,
            List.of(new SplitLineDraft(FOOD_ID, "20", "organic aisle"))));

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
            new SplitEntry(
                55L,
                LocalDate.of(2026, 2, 1),
                CASH_ID,
                null,
                null,
                null,
                List.of(line(FOOD_ID, "20"), line(DEPOSIT_ID, "3"))));

    assertThat(txnId).isEqualTo(55L);
    verify(ledgerService).editTransaction(eq(55L), any());
    verify(ledgerService, never()).recordTransaction(any());
  }

  @Test
  void rejectsanEmptyLineList() {
    SplitEntry entry =
        new SplitEntry(null, LocalDate.of(2026, 2, 1), CASH_ID, null, null, null, List.of());
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockSplitService.commit(entry));
  }

  @Test
  void rejectsanUnknownFundingAccount() {
    when(accountService.findById(CASH_ID)).thenReturn(java.util.Optional.empty());
    SplitEntry entry =
        new SplitEntry(
            null,
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            null,
            null,
            List.of(line(FOOD_ID, "20")));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockSplitService.commit(entry))
        .withMessageContaining("No account");
  }

  private static BigDecimal leg(List<PostingDraft> legs, long accountId) {
    return legs.stream().filter(l -> l.accountId() == accountId).findFirst().orElseThrow().amount();
  }

  private static BigDecimal sum(List<PostingDraft> legs) {
    return legs.stream().map(PostingDraft::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
