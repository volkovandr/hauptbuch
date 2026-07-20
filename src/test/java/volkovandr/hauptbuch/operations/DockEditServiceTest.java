package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
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
import volkovandr.hauptbuch.debts.PersonService;
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
  private static final String ASSET = "asset";
  private static final long TXN_ID = 100L;
  private static final long CASH_ID = 1L;
  private static final long FOOD_ID = 2L;

  @Mock private LedgerService ledgerService;
  @Mock private AccountService accountService;
  @Mock private PayeeService payeeService;
  @Mock private PersonService personService;

  private DockEditService service() {
    return new DockEditService(ledgerService, accountService, payeeService, personService);
  }

  private static Account account(
      long id, String name, String type, Long parentId, String currency) {
    return new Account(id, name, type, parentId, currency, null, null, null, null, false, false);
  }

  private static Account currencyLeaf(long id, String currencyCode, String type, long parentId) {
    return new Account(
        id, currencyCode, type, parentId, currencyCode, null, null, null, null, true, false);
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
  void loadsPersonFundedExpenseWithTheFundingPersonLabel() {
    // "Max pays for a pure expense of yours" (register §2.6 pattern 3): Max's leg is an ordinary
    // asset account, so it classifies as the funding leg exactly like a real account — the person
    // label is the one extra fact the dock's person sub-field needs to redisplay it as "Max", not
    // the leaf's cosmetic internal name.
    long maxLeafId = 9L;
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(maxLeafId, "-20"), posting(FOOD_ID, "20")));
    when(accountService.findById(maxLeafId))
        .thenReturn(Optional.of(account(maxLeafId, "personal.EUR", ASSET, null, EUR)));
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Food", EXPENSE, null, EUR)));
    when(personService.personNameForAccount(maxLeafId)).thenReturn(Optional.of("Max"));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.accountId()).isEqualTo(maxLeafId);
    assertThat(model.fundingPersonLabel()).isEqualTo("Max (EUR)");
  }

  @Test
  void loadsAnOrdinaryExpenseWithNoFundingPersonLabel() {
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "-20"), posting(FOOD_ID, "20")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", ASSET, null, EUR)));
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Food", EXPENSE, null, EUR)));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.fundingPersonLabel()).isNull();
  }

  @Test
  void loadsSimpleIncomeIntoTheDock() {
    // Income debits the own account (Cash +2500) and credits the category (Salary -2500): the
    // funding leg is the own-account leg regardless of its sign, so the dock pre-fills Cash as the
    // account and Salary as the category — never the other way round.
    long salaryId = 4L;
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "2500"), posting(salaryId, "-2500")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", ASSET, null, EUR)));
    when(accountService.findById(salaryId))
        .thenReturn(Optional.of(account(salaryId, "Salary", INCOME, null, EUR)));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.accountId()).isEqualTo(CASH_ID);
    assertThat(model.categoryId()).isEqualTo(salaryId);
    assertThat(model.categoryName()).isEqualTo("Salary");
    assertThat(model.amount()).isEqualTo("2.500,00"); // an inflow is income's default: bare
    assertThat(model.transferDirection()).isNull();
  }

  @Test
  void loadsExpenseRefundIntoTheDock() {
    // A refund inflows the own account (Cash +20, Food -20) — the same shape as income, but the
    // non-default direction for an expense, so the amount keeps its explicit + (register §3.8).
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "20"), posting(FOOD_ID, "-20")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", ASSET, null, EUR)));
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Food", EXPENSE, null, EUR)));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.accountId()).isEqualTo(CASH_ID);
    assertThat(model.categoryId()).isEqualTo(FOOD_ID);
    assertThat(model.amount()).isEqualTo("+20,00");
  }

  @Test
  void loadsCrossCurrencyIncomeIntoTheDock() {
    // The cross-currency counterpart is still the category leg when the own leg is the debit one.
    long salaryChf = 5L;
    Posting cashLeg = posting(CASH_ID, "2500"); // EUR inflow
    Posting salaryLeg =
        new Posting(
            2L,
            TXN_ID,
            salaryChf,
            new BigDecimal("-2400"),
            new BigDecimal("-2500"),
            "unreconciled",
            null);
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID)).thenReturn(List.of(cashLeg, salaryLeg));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", ASSET, null, EUR)));
    when(accountService.findById(salaryChf))
        .thenReturn(Optional.of(currencyLeaf(salaryChf, "CHF", INCOME, 4L)));
    when(accountService.findById(4L))
        .thenReturn(Optional.of(account(4L, "Salary", INCOME, null, EUR)));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.accountId()).isEqualTo(CASH_ID);
    assertThat(model.categoryId()).isEqualTo(4L);
    assertThat(model.categoryCurrencyCode()).isEqualTo("CHF");
    assertThat(model.amount()).isEqualTo("2.500,00");
    assertThat(model.categoryAmount()).isEqualTo("2.400,00");
    assertThat(model.baseAmount()).isEqualTo("2.500,00");
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
  void acceptsTransferWithTwoOwnAccountLegs() {
    // Transfers (7f) are now accepted — two own-account legs with no category.
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "-20"), posting(10L, "20")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    when(accountService.findById(10L))
        .thenReturn(Optional.of(account(10L, "Visa", "liability", null, EUR)));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.transactionId()).isEqualTo(TXN_ID);
    assertThat(model.accountId()).isEqualTo(CASH_ID); // funding account
    assertThat(model.categoryId()).isEqualTo(10L); // transfer target
    assertThat(model.transferDirection()).isEqualTo("TO");
    assertThat(model.categoryAmount()).isNull(); // single-currency, no categoryAmount
  }

  @Test
  void acceptsCrossCurrencyLegCarryingBaseAmount() {
    // Cross-currency categories (7f) are now accepted — a category leg with frozen baseAmount.
    Posting crossLeg =
        new Posting(
            1L, TXN_ID, FOOD_ID, new BigDecimal("20"), new BigDecimal("18"), "unreconciled", null);
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID)).thenReturn(List.of(posting(CASH_ID, "-20"), crossLeg));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Food", EXPENSE, null, "CHF")));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.transactionId()).isEqualTo(TXN_ID);
    assertThat(model.accountId()).isEqualTo(CASH_ID);
    assertThat(model.categoryId()).isEqualTo(FOOD_ID);
    assertThat(model.categoryAmount()).isEqualTo("20,00");
    assertThat(model.baseAmount()).isEqualTo("18,00");
  }

  @Test
  void refusesMissingOrVoidedTransaction() {
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service().load(TXN_ID))
        .withMessageContaining("No live transaction");
  }

  // ── 7f: cross-currency single-line, transfers, and splits with transfers ─────

  @Test
  void loadsCrossCurrencySingleLineExpense() {
    // Cross-currency expense: €20 out of Cash (EUR) → Food (CHF) at a rate.
    // The counterpart leg carries a frozen baseAmount to balance in base currency.
    long foodChf = 3L;
    Posting cashLeg = posting(CASH_ID, "-20"); // EUR outflow
    Posting foodLeg =
        new Posting(
            2L,
            TXN_ID,
            foodChf,
            new BigDecimal("18"),
            new BigDecimal("-20"),
            "unreconciled",
            null); // CHF inflow with frozen base
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID)).thenReturn(List.of(cashLeg, foodLeg));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    when(accountService.findById(foodChf))
        .thenReturn(Optional.of(currencyLeaf(foodChf, "CHF", "expense", FOOD_ID)));
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Food", "expense", null, EUR)));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.transactionId()).isEqualTo(TXN_ID);
    assertThat(model.accountId()).isEqualTo(CASH_ID);
    assertThat(model.categoryId()).isEqualTo(FOOD_ID); // semantic parent
    assertThat(model.categoryName()).isEqualTo("Food");
    assertThat(model.categoryCurrencyCode()).isEqualTo("CHF"); // overridden currency
    assertThat(model.amount()).isEqualTo("20,00"); // funding leg magnitude
    assertThat(model.categoryAmount()).isEqualTo("18,00"); // counterpart leg magnitude
    assertThat(model.baseAmount()).isEqualTo("20,00"); // frozen base (funding leg is base)
  }

  @Test
  void loadsSameCurrencyTransferSingleLine() {
    // Same-currency transfer: €100 from Cash (EUR) to Visa (EUR).
    // Both legs are own accounts; no category.
    long visaId = 11L;
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID))
        .thenReturn(List.of(posting(CASH_ID, "-100"), posting(visaId, "100")));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    when(accountService.findById(visaId))
        .thenReturn(Optional.of(account(visaId, "Visa", "liability", null, EUR)));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.transactionId()).isEqualTo(TXN_ID);
    assertThat(model.accountId()).isEqualTo(CASH_ID); // funding account
    assertThat(model.categoryId()).isEqualTo(visaId); // transfer target (not category)
    assertThat(model.categoryName()).isEqualTo("Visa");
    assertThat(model.amount()).isEqualTo("100,00");
    assertThat(model.transferDirection()).isEqualTo("TO"); // Cash is the funding source
  }

  @Test
  void loadsCrossCurrencyTransferSingleLine() {
    // Cross-currency transfer: €100 from Cash (EUR) to Visa (CHF) at a rate.
    long visaChf = 12L;
    Posting cashLeg = posting(CASH_ID, "-100"); // EUR outflow
    Posting visaLeg =
        new Posting(
            2L,
            TXN_ID,
            visaChf,
            new BigDecimal("90"),
            new BigDecimal("-100"),
            "unreconciled",
            null); // CHF inflow with frozen base
    when(ledgerService.findTransaction(TXN_ID)).thenReturn(Optional.of(txn(null, null)));
    when(ledgerService.findPostings(TXN_ID)).thenReturn(List.of(cashLeg, visaLeg));
    when(accountService.findById(CASH_ID))
        .thenReturn(Optional.of(account(CASH_ID, "Cash", "asset", null, EUR)));
    when(accountService.findById(visaChf))
        .thenReturn(Optional.of(account(visaChf, "Visa", "liability", null, "CHF")));

    DockEditModel model = service().load(TXN_ID);

    assertThat(model.transactionId()).isEqualTo(TXN_ID);
    assertThat(model.accountId()).isEqualTo(CASH_ID);
    assertThat(model.categoryId()).isEqualTo(visaChf); // actual account (not a currency leaf)
    assertThat(model.categoryName()).isEqualTo("Visa");
    assertThat(model.categoryCurrencyCode()).isEqualTo("CHF"); // transfer target currency
    assertThat(model.amount()).isEqualTo("100,00");
    assertThat(model.categoryAmount()).isEqualTo("90,00");
    assertThat(model.baseAmount()).isEqualTo("100,00");
    assertThat(model.transferDirection()).isEqualTo("TO");
  }
}
