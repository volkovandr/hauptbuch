package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
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
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.debts.SettleTarget;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsQuery;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.ledger.TransferTarget;

/**
 * Unit tier (plan §1.5): the settle-up launcher's decision logic with the engine and its
 * collaborators mocked. It proves the pre-scoping a settle reduces to — the leaf as the transfer
 * counterpart, the direction derived from the balance sign, and the amount defaulted to the
 * outstanding figure into the right field — leaving the cross-currency layout itself to {@link
 * CrossCurrencyFieldsService}'s own test and the persistence to the engine's.
 */
@ExtendWith(MockitoExtension.class)
class SettleUpServiceTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final long PERSON_ID = 7L;
  private static final long LEAF_ID = 50L;
  private static final long CASH_EUR = 1L;
  private static final long WALLET_CHF = 2L;
  private static final LocalDate DATE = LocalDate.of(2026, 7, 21);

  @Mock private PersonService personService;
  @Mock private AccountService accountService;
  @Mock private SettingsService settingsService;
  @Mock private CrossCurrencyFieldsService crossCurrencyFieldsService;
  @Mock private DockCommitService dockCommitService;

  private SettleUpService service;

  @BeforeEach
  void setUp() {
    service =
        new SettleUpService(
            personService,
            accountService,
            settingsService,
            crossCurrencyFieldsService,
            dockCommitService);
  }

  private static Account own(long id, String name, String currency) {
    return new Account(id, name, "asset", null, currency, null, null, null, null, false, false);
  }

  private void stubBaseEur() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
  }

  private void stubPerson() {
    when(personService.findById(PERSON_ID))
        .thenReturn(Optional.of(new Person(PERSON_ID, "Max", null)));
  }

  // ── assemble (the form) ─────────────────────────────────────────────────────

  @Test
  void assembleDefaultsToSameCurrencyAccountWithTheOutstandingAmountInTheSingleField() {
    stubBaseEur();
    stubPerson();
    when(personService.settleTarget(PERSON_ID, CHF))
        .thenReturn(Optional.of(new SettleTarget(LEAF_ID, CHF, new BigDecimal("10.00"))));
    when(accountService.findLiveByTypes(any()))
        .thenReturn(List.of(own(CASH_EUR, "Cash", EUR), own(WALLET_CHF, "Wallet", CHF)));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(CrossCurrencyFields.singleCurrency(CHF));

    SettleUpView view = service.assemble(PERSON_ID, CHF, null, DATE);

    // The CHF wallet is pre-selected so the common settle is a single native amount.
    assertThat(view.accounts())
        .filteredOn(SettleUpView.AccountOption::selected)
        .extracting(SettleUpView.AccountOption::accountId)
        .containsExactly(WALLET_CHF);
    assertThat(view.fundingAmountText()).isEqualTo("10,00");
    assertThat(view.summary()).isEqualTo("Max owes you 10,00 CHF");
    assertThat(view.youOwe()).isFalse();

    // The debt-currency figure seeds the funding amount field (not the category field) when single.
    ArgumentCaptor<CrossCurrencyFieldsQuery> query =
        ArgumentCaptor.forClass(CrossCurrencyFieldsQuery.class);
    org.mockito.Mockito.verify(crossCurrencyFieldsService).resolve(query.capture());
    assertThat(query.getValue().fundingCurrencyCode()).isEqualTo(CHF);
    assertThat(query.getValue().fundingAmountText()).isEqualTo("10,00");
    assertThat(query.getValue().categoryAmountText()).isNull();
  }

  @Test
  void assembleCrossCurrencyDefaultsTheDebtFigureIntoTheCategoryLegAndLeavesPaidBlank() {
    stubBaseEur();
    stubPerson();
    when(personService.settleTarget(PERSON_ID, CHF))
        .thenReturn(Optional.of(new SettleTarget(LEAF_ID, CHF, new BigDecimal("10.00"))));
    when(accountService.findLiveByTypes(any())).thenReturn(List.of(own(CASH_EUR, "Cash", EUR)));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(new CrossCurrencyFields(EUR, CHF, true, false, "10,00", null));

    // Only a EUR account exists, so settling a CHF debt is cross-currency.
    SettleUpView view = service.assemble(PERSON_ID, CHF, CASH_EUR, DATE);

    assertThat(view.fundingAmountText()).isEmpty();
    ArgumentCaptor<CrossCurrencyFieldsQuery> query =
        ArgumentCaptor.forClass(CrossCurrencyFieldsQuery.class);
    org.mockito.Mockito.verify(crossCurrencyFieldsService).resolve(query.capture());
    assertThat(query.getValue().fundingCurrencyCode()).isEqualTo(EUR);
    assertThat(query.getValue().categoryAmountText()).isEqualTo("10,00");
    assertThat(query.getValue().fundingAmountText()).isNull();
  }

  @Test
  void assembleWordsNegativeBalanceAsYouOwe() {
    stubBaseEur();
    stubPerson();
    when(personService.settleTarget(PERSON_ID, EUR))
        .thenReturn(Optional.of(new SettleTarget(LEAF_ID, EUR, new BigDecimal("-10.00"))));
    when(accountService.findLiveByTypes(any())).thenReturn(List.of(own(CASH_EUR, "Cash", EUR)));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(CrossCurrencyFields.singleCurrency(EUR));

    SettleUpView view = service.assemble(PERSON_ID, EUR, null, DATE);

    assertThat(view.youOwe()).isTrue();
    assertThat(view.summary()).isEqualTo("You owe Max 10,00"); // base currency renders bare
    assertThat(view.fundingAmountText()).isEqualTo("10,00");
  }

  @Test
  void assembleRejectsPersonWithNoLeafInThatCurrency() {
    when(personService.settleTarget(PERSON_ID, "USD")).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service.assemble(PERSON_ID, "USD", null, DATE))
        .withMessageContaining("Nothing to settle");
  }

  // ── settle (the commit) ─────────────────────────────────────────────────────

  @Test
  void settlePositiveBalanceBooksTransferFromTheLeaf() {
    when(personService.settleTarget(PERSON_ID, CHF))
        .thenReturn(Optional.of(new SettleTarget(LEAF_ID, CHF, new BigDecimal("10.00"))));

    service.settle(PERSON_ID, CHF, CASH_EUR, DATE, "9,20", "10,00", null);

    DockEntry entry = capturedEntry();
    // They owe you → they pay you in → money comes FROM the leaf into the funding account.
    assertThat(entry.transferDirection()).isEqualTo(TransferTarget.Direction.FROM.name());
    assertThat(entry.categoryId()).isEqualTo(LEAF_ID); // the leaf is the transfer counterpart
    assertThat(entry.accountId()).isEqualTo(CASH_EUR);
    assertThat(entry.amount()).isEqualTo("9,20");
    assertThat(entry.categoryAmount()).isEqualTo("10,00");
    assertThat(entry.personName()).isNull(); // settled by leaf id, not the for/by name path
    assertThat(entry.transactionId()).isNull(); // a new transaction
  }

  @Test
  void settleNegativeBalanceBooksTransferToTheLeaf() {
    when(personService.settleTarget(PERSON_ID, EUR))
        .thenReturn(Optional.of(new SettleTarget(LEAF_ID, EUR, new BigDecimal("-10.00"))));

    service.settle(PERSON_ID, EUR, CASH_EUR, DATE, "10,00", null, null);

    DockEntry entry = capturedEntry();
    // You owe them → you pay out → money goes TO the leaf.
    assertThat(entry.transferDirection()).isEqualTo(TransferTarget.Direction.TO.name());
    assertThat(entry.categoryId()).isEqualTo(LEAF_ID);
  }

  @Test
  void settleRejectsPersonWithNoLeafInThatCurrency() {
    when(personService.settleTarget(PERSON_ID, "USD")).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service.settle(PERSON_ID, "USD", CASH_EUR, DATE, "10,00", null, null))
        .withMessageContaining("Nothing to settle");
  }

  private DockEntry capturedEntry() {
    ArgumentCaptor<DockEntry> captor = ArgumentCaptor.forClass(DockEntry.class);
    org.mockito.Mockito.verify(dockCommitService).commit(captor.capture());
    return captor.getValue();
  }
}
