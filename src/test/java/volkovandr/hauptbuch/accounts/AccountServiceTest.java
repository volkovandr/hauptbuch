package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * Unit tier (plan §1.5): account management's validation and orchestration logic with the DB mocked
 * away — the rules that must reject bad input <em>before</em> any insert: blank names, unmanaged
 * types, parents that would break leaves-only (data-model §5) — plus the hue assignment (register
 * §2.8) and the opening-balance hand-off to the engine's {@link OpeningBalanceRecorder}.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

  private static final String EUR = "EUR";
  private static final String GIRO = "Giro";
  private static final String ASSET = "asset";
  private static final LocalDate OPENED = LocalDate.of(2026, 7, 1);

  private static final long NEW_ID = 42L;
  private static final long PARENT_ID = 7L;

  @Mock private AccountRepository accountRepository;
  @Mock private OpeningBalanceRecorder openingBalanceRecorder;

  private AccountService accountService;

  @BeforeEach
  void setUp() {
    // The provider stands in for Spring's lazy resolution (see the service's constructor javadoc).
    ObjectProvider<OpeningBalanceRecorder> provider =
        new ObjectProvider<>() {
          @Override
          public OpeningBalanceRecorder getObject() {
            return openingBalanceRecorder;
          }
        };
    accountService = new AccountService(accountRepository, provider);
  }

  private static Account account(long id, String name, String type, Integer hue) {
    return new Account(id, name, type, null, EUR, hue, OPENED, null, null, false);
  }

  private void stubInsertReturning(long id) {
    when(accountRepository.insert(any())).thenReturn(id);
    when(accountRepository.findById(id)).thenReturn(Optional.of(account(id, GIRO, ASSET, 210)));
  }

  @Test
  void opensAccountAndAssignsTheFirstFreePaletteHue() {
    when(accountRepository.findLiveByTypes(any()))
        .thenReturn(List.of(account(1L, "Cash", ASSET, AccountService.HUE_PALETTE.get(0))));
    stubInsertReturning(NEW_ID);

    accountService.openAccount(new AccountDraft(GIRO, ASSET, null, EUR, OPENED, null));

    ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).insert(captor.capture());
    // The first palette hue is taken by Cash; the new account gets the second.
    assertThat(captor.getValue().hue()).isEqualTo(AccountService.HUE_PALETTE.get(1));
    assertThat(captor.getValue().openedAt()).isEqualTo(OPENED);
  }

  @Test
  void reusesTheLeastUsedHueWhenThePaletteIsExhausted() {
    // Every palette hue is used once, the first one twice — the second is among the least used.
    List<Account> existing =
        new ArrayList<>(
            AccountService.HUE_PALETTE.stream().map(h -> account(h, "a" + h, ASSET, h)).toList());
    existing.add(account(900L, "extra", ASSET, AccountService.HUE_PALETTE.get(0)));
    when(accountRepository.findLiveByTypes(any())).thenReturn(existing);
    stubInsertReturning(NEW_ID);

    accountService.openAccount(new AccountDraft(GIRO, ASSET, null, EUR, OPENED, null));

    ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).insert(captor.capture());
    assertThat(captor.getValue().hue()).isEqualTo(AccountService.HUE_PALETTE.get(1));
  }

  @Test
  void nonZeroOpeningBalanceIsHandedToTheEngine() {
    when(accountRepository.findLiveByTypes(any())).thenReturn(List.of());
    stubInsertReturning(NEW_ID);

    accountService.openAccount(
        new AccountDraft(GIRO, ASSET, null, EUR, OPENED, new BigDecimal("1234.56")));

    verify(openingBalanceRecorder).recordOpeningBalance(NEW_ID, new BigDecimal("1234.56"), OPENED);
  }

  @Test
  void absentOrZeroOpeningBalanceBooksNothing() {
    when(accountRepository.findLiveByTypes(any())).thenReturn(List.of());
    stubInsertReturning(NEW_ID);

    accountService.openAccount(new AccountDraft(GIRO, ASSET, null, EUR, OPENED, null));
    accountService.openAccount(
        new AccountDraft(GIRO, ASSET, null, EUR, OPENED, new BigDecimal("0.00")));

    verify(openingBalanceRecorder, never()).recordOpeningBalance(anyLong(), any(), any());
  }

  @Test
  void rejectsBlankNameBeforeAnyInsert() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                accountService.openAccount(new AccountDraft("  ", ASSET, null, EUR, OPENED, null)))
        .withMessageContaining("name");

    verify(accountRepository, never()).insert(any());
  }

  @Test
  void rejectsTypesTheAccountsScreenDoesNotManage() {
    // Categories (income/expense) and equity are not opened here — 6b and system plumbing.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                accountService.openAccount(
                    new AccountDraft("Food", "expense", null, EUR, OPENED, null)))
        .withMessageContaining("type");

    verify(accountRepository, never()).insert(any());
  }

  @Test
  void rejectsParentOfDifferentType() {
    when(accountRepository.findById(PARENT_ID))
        .thenReturn(Optional.of(account(PARENT_ID, "Visa", "liability", null)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                accountService.openAccount(
                    new AccountDraft(GIRO, ASSET, PARENT_ID, EUR, OPENED, null)))
        .withMessageContaining("liability");

    verify(accountRepository, never()).insert(any());
  }

  @Test
  void rejectsPostedParentUntilSubdivisionExists() {
    when(accountRepository.findById(PARENT_ID))
        .thenReturn(Optional.of(account(PARENT_ID, "Cash", ASSET, null)));
    when(accountRepository.hasPostings(PARENT_ID)).thenReturn(true);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                accountService.openAccount(
                    new AccountDraft("Wallet", ASSET, PARENT_ID, EUR, OPENED, null)))
        .withMessageContaining("leaves-only");

    verify(accountRepository, never()).insert(any());
  }

  @Test
  void parentOptionsOfferParentsAndNeverPostedAccountsOnly() {
    Account parent = account(1L, "Cards", "liability", null);
    Account posted = account(2L, "Cash", ASSET, 210);
    Account fresh = account(3L, GIRO, ASSET, 30);
    Account closed =
        new Account(
            4L, "Old", ASSET, null, EUR, 140, OPENED, LocalDate.of(2026, 7, 2), null, false);
    when(accountRepository.findLiveByTypes(any()))
        .thenReturn(List.of(parent, posted, fresh, closed));
    when(accountRepository.findPostedAccountIds()).thenReturn(List.of(2L));
    when(accountRepository.findParentAccountIds()).thenReturn(List.of(1L));

    assertThat(accountService.parentOptions()).containsExactly(parent, fresh);
  }

  @Test
  void updatesTheFreelyEditableFields() {
    when(accountRepository.findById(NEW_ID))
        .thenReturn(Optional.of(account(NEW_ID, GIRO, ASSET, 210)));

    accountService.updateAccount(NEW_ID, "Girokonto", 30);

    verify(accountRepository).updateNameAndHue(NEW_ID, "Girokonto", 30);
  }

  @Test
  void refusesToEditSystemOrCategoryAccounts() {
    when(accountRepository.findById(NEW_ID))
        .thenReturn(Optional.of(account(NEW_ID, "Opening Balances EUR", "equity", null)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> accountService.updateAccount(NEW_ID, "Renamed", null))
        .withMessageContaining("not edited here");

    verify(accountRepository, never()).updateNameAndHue(anyLong(), any(), any());
  }

  @Test
  void rejectsHueOffTheColourWheel() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> accountService.updateAccount(NEW_ID, GIRO, 360))
        .withMessageContaining("colour wheel");
  }

  @Test
  void closesOpenAccountAndRefusesSecondClose() {
    when(accountRepository.findById(NEW_ID))
        .thenReturn(Optional.of(account(NEW_ID, GIRO, ASSET, 210)));
    when(accountRepository.close(NEW_ID, OPENED)).thenReturn(1).thenReturn(0);

    accountService.closeAccount(NEW_ID, OPENED);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> accountService.closeAccount(NEW_ID, OPENED))
        .withMessageContaining("already closed");
  }

  @Test
  void reopensClosedAccountAndRefusesWhenNotClosed() {
    when(accountRepository.findById(NEW_ID))
        .thenReturn(Optional.of(account(NEW_ID, GIRO, ASSET, 210)));
    when(accountRepository.reopen(NEW_ID)).thenReturn(1).thenReturn(0);

    accountService.reopenAccount(NEW_ID);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> accountService.reopenAccount(NEW_ID))
        .withMessageContaining("not closed");
  }

  // ── currency leaves (data-model §6.5, plan stage 7d.1 follow-up) ─────────────

  @Test
  void insertCurrencyLeafIsNamedAfterTheBareCurrencyCodeAndMarked() {
    Account chfLeaf =
        new Account(NEW_ID, "CHF", "expense", PARENT_ID, "CHF", null, null, null, null, true);
    when(accountRepository.insert(any())).thenReturn(NEW_ID);
    when(accountRepository.findById(NEW_ID)).thenReturn(Optional.of(chfLeaf));

    Account result = accountService.insertCurrencyLeaf("CHF", "expense", PARENT_ID);

    assertThat(result).isEqualTo(chfLeaf);
    ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).insert(captor.capture());
    assertThat(captor.getValue().name()).isEqualTo("CHF");
    assertThat(captor.getValue().currencyCode()).isEqualTo("CHF");
    assertThat(captor.getValue().parentId()).isEqualTo(PARENT_ID);
    assertThat(captor.getValue().currencyLeaf()).isTrue();
  }

  @Test
  void insertLeafIsNeverMarkedAsCurrencyLeaf() {
    stubInsertReturning(NEW_ID);

    accountService.insertLeaf(GIRO, ASSET, null, EUR);

    ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository).insert(captor.capture());
    assertThat(captor.getValue().currencyLeaf()).isFalse();
  }

  @Test
  void reparentDelegatesToTheRepository() {
    accountService.reparent(NEW_ID, PARENT_ID);

    verify(accountRepository).updateParent(NEW_ID, PARENT_ID);
  }

  // ── transfer counterpart resolution (register §3.5, plan stage 7d.3) ──────────

  @Test
  void findsAnOpenOwnAccountByNameCaseInsensitively() {
    when(accountRepository.findLiveByTypes(any()))
        .thenReturn(List.of(account(1L, "Cash", ASSET, 210), account(2L, "Visa", "liability", 30)));

    assertThat(accountService.findOwnAccountByName("visa").orElseThrow().accountId()).isEqualTo(2L);
  }

  @Test
  void doesNotResolveNameWithNoOpenOwnAccount() {
    when(accountRepository.findLiveByTypes(any()))
        .thenReturn(List.of(account(1L, "Cash", ASSET, 210)));

    assertThat(accountService.findOwnAccountByName("Giro")).isEmpty();
  }

  @Test
  void refusesToGuessAnAmbiguousName() {
    // Two open own accounts share a name — resolving it would guess, so it is refused (empty).
    when(accountRepository.findLiveByTypes(any()))
        .thenReturn(List.of(account(1L, "Wallet", ASSET, 210), account(2L, "Wallet", ASSET, 30)));

    assertThat(accountService.findOwnAccountByName("Wallet")).isEmpty();
  }

  @Test
  void doesNotResolveClosedOwnAccount() {
    Account closed = new Account(3L, "Old", ASSET, null, EUR, 140, OPENED, OPENED, null, false);
    when(accountRepository.findLiveByTypes(any())).thenReturn(List.of(closed));

    assertThat(accountService.findOwnAccountByName("Old")).isEmpty();
  }
}
