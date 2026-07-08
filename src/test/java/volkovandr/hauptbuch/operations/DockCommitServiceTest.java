package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
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
import volkovandr.hauptbuch.ledger.TransactionDraft;

/**
 * Unit tier (plan §1.5): the dock commit path's decision logic with the engine and its
 * collaborators mocked — sign resolution (register §3.8, the load-bearing part) and the
 * orchestration that turns a dock entry into a balanced pair of legs. The engine's own invariants
 * and the per-currency-leaf routing are proven in their own tests; here we prove the dock assembles
 * the right draft.
 */
@ExtendWith(MockitoExtension.class)
class DockCommitServiceTest {

  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";
  private static final String EUR = "EUR";
  private static final long CASH_ID = 1L;
  private static final long CATEGORY_ID = 2L;
  private static final long LEAF_ID = 3L;

  @Mock private AccountService accountService;
  @Mock private PayeeService payeeService;
  @Mock private CurrencyLeafService currencyLeafService;
  @Mock private LedgerService ledgerService;

  private DockCommitService dockCommitService;

  @BeforeEach
  void setUp() {
    dockCommitService =
        new DockCommitService(accountService, payeeService, currencyLeafService, ledgerService);
  }

  private static Account account(long id, String type, String currency) {
    return new Account(id, "n", type, null, currency, null, null, null, null);
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

  // ── orchestration ───────────────────────────────────────────────────────────

  @Test
  void commitAssemblesBalancedPairAndRecordsIt() {
    Account cash = account(CASH_ID, "asset", EUR);
    Account leaf = account(LEAF_ID, EXPENSE, EUR);
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(cash));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, EUR)).thenReturn(leaf);
    when(payeeService.resolvePayee(42L, null)).thenReturn(42L);
    when(ledgerService.recordTransaction(any())).thenReturn(99L);

    DockEntry entry =
        new DockEntry(LocalDate.of(2026, 2, 1), CASH_ID, 42L, null, CATEGORY_ID, "20", "lunch");
    long txnId = dockCommitService.commit(entry);

    assertThat(txnId).isEqualTo(99L);

    ArgumentCaptor<TransactionDraft> draft = ArgumentCaptor.forClass(TransactionDraft.class);
    verify(ledgerService).recordTransaction(draft.capture());
    List<PostingDraft> legs = draft.getValue().postings();
    // Cash −20 / Food +20 — a balanced single-currency expense (register §3.8).
    assertThat(legs).hasSize(2);
    assertThat(leg(legs, CASH_ID)).isEqualByComparingTo("-20");
    assertThat(leg(legs, LEAF_ID)).isEqualByComparingTo("20");
    assertThat(draft.getValue().payeeId()).isEqualTo(42L);
    assertThat(draft.getValue().note()).isEqualTo("lunch");
    assertThat(draft.getValue().date()).isEqualTo(LocalDate.of(2026, 2, 1));
  }

  @Test
  void commitRoutesTheCategoryToTheFundingAccountsCurrency() {
    // The paying account is CHF, so the category leaf must be resolved in CHF (§6.5) — the dock
    // never
    // hand-picks the currency leaf.
    Account chfCard = account(CASH_ID, "asset", "CHF");
    Account chfLeaf = account(LEAF_ID, EXPENSE, "CHF");
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(chfCard));
    when(currencyLeafService.resolveCurrencyLeaf(CATEGORY_ID, "CHF")).thenReturn(chfLeaf);
    when(payeeService.resolvePayee(null, "Migros - Switzerland")).thenReturn(7L);
    when(ledgerService.recordTransaction(any())).thenReturn(1L);

    DockEntry entry =
        new DockEntry(
            LocalDate.of(2026, 2, 1),
            CASH_ID,
            null,
            "Migros - Switzerland",
            CATEGORY_ID,
            "10",
            null);
    dockCommitService.commit(entry);

    verify(currencyLeafService).resolveCurrencyLeaf(CATEGORY_ID, "CHF");
    verify(payeeService).resolvePayee(null, "Migros - Switzerland");
  }

  @Test
  void commitRejectsAnUnknownFundingAccount() {
    when(accountService.findById(CASH_ID)).thenReturn(Optional.empty());

    DockEntry entry =
        new DockEntry(LocalDate.of(2026, 2, 1), CASH_ID, null, null, CATEGORY_ID, "10", null);
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> dockCommitService.commit(entry))
        .withMessageContaining("No account");
  }

  private static BigDecimal leg(List<PostingDraft> legs, long accountId) {
    return legs.stream().filter(l -> l.accountId() == accountId).findFirst().orElseThrow().amount();
  }
}
