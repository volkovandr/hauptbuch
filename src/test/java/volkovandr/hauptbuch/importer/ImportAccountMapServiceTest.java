package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportAccountMapService} resolution logic with every repository
 * and cross-module service mocked — mapping a Money account name to an existing Hauptbuch account,
 * a new one (import.md §5.1; plan c1), or a person (§5.4; plan c2); the {@code expect-file} toggle;
 * the many-to-one target; and the guards (row not in the open session, a non-mappable target
 * account, an unknown person, a missing currency).
 */
@ExtendWith(MockitoExtension.class)
class ImportAccountMapServiceTest {

  private static final long SESSION_ID = 1L;

  @Mock ImportSessionService importSessionService;
  @Mock ImportAccountRepository importAccountRepository;
  @Mock AccountService accountService;
  @Mock PersonService personService;
  @Mock PersonProvisioningService personProvisioningService;
  @Mock CurrencyService currencyService;

  private ImportAccountMapService service() {
    return new ImportAccountMapService(
        importSessionService,
        importAccountRepository,
        accountService,
        personService,
        personProvisioningService,
        currencyService);
  }

  private void openSession() {
    when(importSessionService.currentSession())
        .thenReturn(
            Optional.of(
                new ImportSession(
                    SESSION_ID, ImportSessionState.OPEN, null, null, OffsetDateTime.now(), null)));
  }

  private static ImportAccount unmapped(long id, String name) {
    return new ImportAccount(id, SESSION_ID, name, null, null, null, true, null, null);
  }

  private static Account account(long id, String name, String type) {
    return new Account(id, name, type, null, "EUR", null, null, null, null, false, false, false);
  }

  @Test
  void mapsToAnExistingAccount() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Current Account")));
    when(accountService.findById(42L)).thenReturn(Optional.of(account(42L, "Giro", "asset")));

    service().mapToExisting(10L, 42L);

    verify(importAccountRepository).mapToAccount(10L, 42L, null);
  }

  @Test
  void rejectsAnUnknownExistingAccount() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Current Account")));
    when(accountService.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().mapToExisting(10L, 99L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("99");

    verify(importAccountRepository, never()).mapToAccount(anyLong(), anyLong(), any());
  }

  @Test
  void rejectsMappingToCategoryOrPersonLeafAccount() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Current Account")));
    when(accountService.findById(7L)).thenReturn(Optional.of(account(7L, "Food", "expense")));

    assertThatThrownBy(() -> service().mapToExisting(10L, 7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("asset");

    verify(importAccountRepository, never()).mapToAccount(anyLong(), anyLong(), any());
  }

  @Test
  void rejectsAnImportAccountRowThatIsNotInTheOpenSession() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Current Account")));

    assertThatThrownBy(() -> service().mapToExisting(999L, 42L))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(accountService);
    verify(importAccountRepository, never()).mapToAccount(anyLong(), anyLong(), any());
  }

  @Test
  void mapToExistingWithoutAnOpenSessionIsRejected() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().mapToExisting(10L, 42L))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(importAccountRepository, accountService);
  }

  @Test
  void createsNewAccountFromChosenTypeAndCurrencyThenMapsToIt() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Bank24ru-EUR")));
    when(currencyService.exists("CHF")).thenReturn(true);
    when(accountService.openAccount(any(AccountDraft.class)))
        .thenReturn(account(55L, "Bank24", "liability"));

    service().mapToNew(10L, "Bank24", "liability", "CHF");

    verify(accountService)
        .openAccount(new AccountDraft("Bank24", "liability", null, "CHF", null, null));
    verify(importAccountRepository).mapToAccount(10L, 55L, "CHF");
  }

  @Test
  void newAccountNeedsCurrency() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Bank24ru-EUR")));

    assertThatThrownBy(() -> service().mapToNew(10L, "Bank24", "asset", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency");

    verify(accountService, never()).openAccount(any());
    verify(importAccountRepository, never()).mapToAccount(anyLong(), anyLong(), any());
  }

  @Test
  void rejectsUnknownCurrencyForNewAccount() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Bank24ru-EUR")));
    when(currencyService.exists("XXX")).thenReturn(false);

    assertThatThrownBy(() -> service().mapToNew(10L, "Bank24", "asset", "XXX"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("XXX");

    verify(accountService, never()).openAccount(any());
    verify(importAccountRepository, never()).mapToAccount(anyLong(), anyLong(), any());
  }

  @Test
  void newAccountRowMustBeInTheOpenSessionBeforeTheAccountIsCreated() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Current Account")));

    assertThatThrownBy(() -> service().mapToNew(999L, "Ghost", "asset", "EUR"))
        .isInstanceOf(IllegalArgumentException.class);

    verify(accountService, never()).openAccount(any());
  }

  @Test
  void mapsToAnExistingPersonsResolvedLeaf() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(20L, "Loan to Max")));
    when(currencyService.exists("EUR")).thenReturn(true);
    when(personService.findById(8L)).thenReturn(Optional.of(new Person(8L, "Max", null)));
    when(personProvisioningService.ensureLeaf(8L, "EUR"))
        .thenReturn(account(99L, "personal.EUR", "asset"));

    service().mapToPerson(20L, 8L, null, "EUR");

    // The row holds an ordinary account id (the leaf) — f2 books it like any account (§5.4).
    verify(importAccountRepository).mapToAccount(20L, 99L, "EUR");
  }

  @Test
  void mapsToNamedPersonProvisioningTheLeaf() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(20L, "Loan to Bob")));
    when(currencyService.exists("CHF")).thenReturn(true);
    when(personProvisioningService.ensureLeaf("Bob", "CHF", false))
        .thenReturn(account(55L, "personal.CHF", "asset"));

    service().mapToPerson(20L, null, "Bob", "CHF");

    verify(importAccountRepository).mapToAccount(20L, 55L, "CHF");
    verify(personService, never()).findById(anyLong());
  }

  @Test
  void personMappingNeedsPersonOrName() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(20L, "Loan to Max")));
    when(currencyService.exists("EUR")).thenReturn(true);

    assertThatThrownBy(() -> service().mapToPerson(20L, null, "  ", "EUR"))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(personProvisioningService);
    verify(importAccountRepository, never()).mapToAccount(anyLong(), anyLong(), any());
  }

  @Test
  void personMappingNeedsCurrency() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(20L, "Loan to Max")));

    assertThatThrownBy(() -> service().mapToPerson(20L, 8L, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("currency");

    verifyNoInteractions(personService, personProvisioningService);
    verify(importAccountRepository, never()).mapToAccount(anyLong(), anyLong(), any());
  }

  @Test
  void personMappingRejectsAnUnknownPersonId() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(20L, "Loan to Max")));
    when(currencyService.exists("EUR")).thenReturn(true);
    when(personService.findById(77L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().mapToPerson(20L, 77L, null, "EUR"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("77");

    verifyNoInteractions(personProvisioningService);
    verify(importAccountRepository, never()).mapToAccount(anyLong(), anyLong(), any());
  }

  @Test
  void setsAndClearsExpectFileForOneRowInTheOpenSession() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Current Account")));

    service().setExpectFile(10L, false);

    verify(importAccountRepository).setExpectFile(10L, false);
  }

  @Test
  void setExpectFileRejectsUnknownRow() {
    openSession();
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Current Account")));

    assertThatThrownBy(() -> service().setExpectFile(999L, false))
        .isInstanceOf(IllegalArgumentException.class);

    verify(importAccountRepository, never()).setExpectFile(anyLong(), anyBoolean());
  }
}
