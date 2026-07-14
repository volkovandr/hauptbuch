package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.Posting;
import volkovandr.hauptbuch.ledger.Transaction;
import volkovandr.hauptbuch.ledger.TransactionTag;

/**
 * Unit tier (plan §1.5): the edit-mode loader's real logic — reconstructing the sign-free amount
 * text (register §3.8) so a re-save round-trips, and classifying a transaction's legs into the
 * dock's simple shape (one funding leg, one category leg) while refusing the shapes the dock can't
 * edit yet. The ledger/account/payee reads are mocked; persistence is proven elsewhere.
 */
@ExtendWith(MockitoExtension.class)
class DockEditServiceTest {

  private static final String EUR = "EUR";
  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";
  private static final long TXN_ID = 100L;
  private static final long CASH_ID = 1L;
  private static final long FOOD_ID = 2L;

  @Mock private LedgerService ledgerService;
  @Mock private AccountService accountService;
  @Mock private PayeeService payeeService;

  private DockEditService service() {
    return new DockEditService(ledgerService, accountService, payeeService);
  }

  private static Account account(
      long id, String name, String type, Long parentId, String currency) {
    return new Account(id, name, type, parentId, currency, null, null, null, null, false);
  }

  private static Account currencyLeaf(long id, String currencyCode, String type, long parentId) {
    return new Account(
        id, currencyCode, type, parentId, currencyCode, null, null, null, null, true);
  }

  private static Posting posting(long accountId, String amount) {
    return new Posting(1L, TXN_ID, accountId, new BigDecimal(amount), null, "unreconciled", null);
  }

  private static Transaction txn(Long payeeId, String note) {
    return new Transaction(
        TXN_ID, LocalDate.of(2026, 2, 1), payeeId, note, "confirmed", null, null, null);
  }

  // ── amount reconstruction (register §3.8) ─────────────────────────────────────

  @Test
  void expenseOutflowRendersAsBareMagnitude() {
    assertThat(DockEditService.amountText(new BigDecimal("-20"), EXPENSE)).isEqualTo("20,00");
  }

  @Test
  void expenseRefundInflowCarriesAnExplicitPlus() {
    assertThat(DockEditService.amountText(new BigDecimal("20"), EXPENSE)).isEqualTo("+20,00");
  }

  @Test
  void incomeInflowRendersAsBareMagnitude() {
    assertThat(DockEditService.amountText(new BigDecimal("2500"), INCOME)).isEqualTo("2.500,00");
  }

  @Test
  void incomeReversalOutflowCarriesAnExplicitMinus() {
    assertThat(DockEditService.amountText(new BigDecimal("-15"), INCOME)).isEqualTo("-15,00");
  }

  // ── leg classification ────────────────────────────────────────────────────────

  @Test
  void loadsSimpleExpenseIntoTheDock() {
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(42L, "lunch")));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "-20"), posting(FOOD_ID, "20")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Food", EXPENSE, null, EUR)));
    when(payeeService.entryValueFor(42L)).thenReturn(Optional.of("Rewe - Dortmund - Germany"));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.transactionId()).isEqualTo(TXN_ID);
    assertThat(model.accountId()).isEqualTo(CASH_ID);
    assertThat(model.categoryId()).isEqualTo(FOOD_ID);
    assertThat(model.categoryName()).isEqualTo("Food");
    assertThat(model.amount()).isEqualTo("20,00");
    assertThat(model.payeeText()).isEqualTo("Rewe - Dortmund - Germany");
    assertThat(model.note()).isEqualTo("lunch");
  }

  @Test
  void prefillsTheTransactionTagsAsChips() {
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "-20"), posting(FOOD_ID, "20")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Food", EXPENSE, null, EUR)));
    when(ledgerService.tagsForTransaction(TXN_ID))
        .thenReturn(List.of(new TransactionTag(3L, "Car:Passat"), new TransactionTag(5L, "Trip")));

    DockEditModel model = service().load(TXN_ID);

    // The dock re-renders these as pills so a re-save preserves them (register §3.6).
    assertThat(model.tags())
        .extracting(TransactionTag::label)
        .containsExactly("Car:Passat", "Trip");
  }

  @Test
  void resolvesThePerCurrencyLeafBackToItsSemanticParent() {
    // The category leg hits an auto-managed EUR currency leaf (a §6.5 per-currency child of
    // "Food"); the dock should pre-fill the semantic parent "Food" so a re-save routes back
    // through resolveCurrencyLeaf.
    long foodEur = 3L;
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "-20"), posting(foodEur, "20")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    when(accountService.findById(foodEur))
        .thenReturn(Optional.of(currencyLeaf(foodEur, EUR, EXPENSE, FOOD_ID)));
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Food", EXPENSE, null, EUR)));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.categoryId()).isEqualTo(FOOD_ID);
    assertThat(model.categoryName()).isEqualTo("Food");
    assertThat(model.payeeText()).isNull();
  }

  @Test
  void refusesAnOpeningBalanceWithAnEquityLeg() {
    when(ledgerService.findTransaction(TXN_ID))
        .thenReturn(Optional.of(txn(null, "Opening balance")));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "500"), posting(9L, "-500")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    when(accountService.findById(9L))
        .thenReturn(Optional.of(account(9L, "Opening Balances EUR", "equity", null, EUR)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service().load(TXN_ID))
        .withMessageContaining("cannot be edited");
  }

  @Test
  void refusesTransferWithTwoOwnAccountLegs() {
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "-20"), posting(10L, "20")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    lenient()
        .when(accountService.findById(10L))
        .thenReturn(Optional.of(account(10L, "Visa", "liability", null, EUR)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service().load(TXN_ID))
        .withMessageContaining("cannot be edited");
  }

  @Test
  void refusesCrossCurrencyLegCarryingBaseAmount() {
    Posting crossLeg =
        new Posting(
            1L, TXN_ID, FOOD_ID, new BigDecimal("20"), new BigDecimal("18"), "unreconciled", null);
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID)).thenReturn(List.of(posting(CASH_ID, "-20"), crossLeg));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    lenient()
        .when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Food", EXPENSE, null, "CHF")));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service().load(TXN_ID))
        .withMessageContaining("cannot be edited");
  }

  @Test
  void refusesMissingOrVoidedTransaction() {
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service().load(TXN_ID))
        .withMessageContaining("No live transaction");
  }
}
