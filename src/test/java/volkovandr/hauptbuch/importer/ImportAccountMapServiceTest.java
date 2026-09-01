package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportFileRepository;
import volkovandr.hauptbuch.ledger.Currency;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportAccountMapService} resolution logic with every repository
 * and cross-module service mocked — mapping a Money account name to an existing Hauptbuch account
 * or to a new one (import.md §5.1; plan c1), the many-to-one target, and the guards (row not in the
 * open session, a non-mappable target account).
 */
@ExtendWith(MockitoExtension.class)
class ImportAccountMapServiceTest {

  private static final long SESSION_ID = 1L;

  @Mock ImportSessionService importSessionService;
  @Mock ImportAccountRepository importAccountRepository;
  @Mock ImportFileRepository importFileRepository;
  @Mock AccountService accountService;
  @Mock CurrencyService currencyService;

  private ImportAccountMapService service() {
    return new ImportAccountMapService(
        importSessionService,
        importAccountRepository,
        importFileRepository,
        accountService,
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

  private static ImportAccount mappedTo(long id, String name, long accountId) {
    return new ImportAccount(id, SESSION_ID, name, accountId, null, null, true, null, null);
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
  void buildsPanelWithResolvedNamesProposedTypesAndManyToOneTarget() {
    when(importAccountRepository.findBySession(SESSION_ID))
        .thenReturn(
            List.of(
                unmapped(10L, "Cash"), mappedTo(11L, "Junk A", 42L), mappedTo(12L, "Junk B", 42L)));
    when(importFileRepository.findBySession(SESSION_ID))
        .thenReturn(
            List.of(file("Cash", "asset"), file("Junk A", null), file("Junk A", "liability")));
    when(accountService.manageableAccounts())
        .thenReturn(List.of(account(42L, "Everything Else", "asset")));
    when(currencyService.findAll()).thenReturn(List.of(new Currency("EUR", 2, "€", "Euro")));

    ImportAccountMap panel = service().mapPanel(SESSION_ID);

    assertThat(panel.rows())
        .satisfiesExactly(
            cash -> {
              assertThat(cash.moneyAccountName()).isEqualTo("Cash");
              assertThat(cash.mapped()).isFalse();
              assertThat(cash.targetName()).isNull();
              assertThat(cash.proposedType()).isEqualTo("asset");
            },
            junkA -> {
              assertThat(junkA.mapped()).isTrue();
              assertThat(junkA.targetAccountId()).isEqualTo(42L);
              assertThat(junkA.targetName()).isEqualTo("Everything Else");
              // first non-null header wins
              assertThat(junkA.proposedType()).isEqualTo("liability");
            },
            junkB -> {
              assertThat(junkB.targetName()).isEqualTo("Everything Else");
              assertThat(junkB.proposedType()).isNull();
            });
    assertThat(panel.accountOptions())
        .singleElement()
        .satisfies(
            option -> {
              assertThat(option.accountId()).isEqualTo(42L);
              assertThat(option.name()).isEqualTo("Everything Else");
            });
    assertThat(panel.currencyOptions())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.code()).isEqualTo("EUR");
              assertThat(c.name()).isEqualTo("Euro");
            });
  }

  @Test
  void mapPanelIsEmptyWhenNothingHasBeenStaged() {
    when(importAccountRepository.findBySession(SESSION_ID)).thenReturn(List.of());
    when(importFileRepository.findBySession(SESSION_ID)).thenReturn(List.of());
    when(accountService.manageableAccounts()).thenReturn(List.of());
    when(currencyService.findAll()).thenReturn(List.of());

    assertThat(service().mapPanel(SESSION_ID).rows()).isEmpty();
  }

  private static ImportFile file(String moneyAccountName, String proposedType) {
    return new ImportFile(
        1L,
        SESSION_ID,
        "export.qif",
        moneyAccountName,
        "utf_8",
        "day_month",
        proposedType,
        0,
        OffsetDateTime.now());
  }
}
