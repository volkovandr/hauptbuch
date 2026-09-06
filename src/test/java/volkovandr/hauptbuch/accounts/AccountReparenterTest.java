package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * Unit tier (plan §1.5): the account editor's re-parent move (issue account-management/03) with the
 * DB mocked away. Drives the real {@link AccountReparenter} over a real {@link AccountService} — so
 * the reused create-path rules ({@code requireManageable}, {@code requireUsableParent}) run for
 * real — with both sharing one mocked {@link AccountRepository}.
 */
@ExtendWith(MockitoExtension.class)
class AccountReparenterTest {

  private static final String EUR = "EUR";
  private static final String ASSET = "asset";
  private static final String EXPENSE = "expense";
  private static final LocalDate OPENED = LocalDate.of(2026, 7, 1);

  private static final long ACCOUNT_ID = 42L;
  private static final long PARENT_ID = 7L;

  @Mock private AccountRepository accountRepository;
  @Mock private OpeningBalanceRecorder openingBalanceRecorder;

  private AccountReparenter reparenter;

  @BeforeEach
  void setUp() {
    ObjectProvider<OpeningBalanceRecorder> provider =
        new ObjectProvider<>() {
          @Override
          public OpeningBalanceRecorder getObject() {
            return openingBalanceRecorder;
          }
        };
    reparenter =
        new AccountReparenter(new AccountService(accountRepository, provider), accountRepository);
  }

  private static Account account(long id, String name, String type) {
    return new Account(id, name, type, null, EUR, null, OPENED, null, null, false, false, false);
  }

  private static Account child(long id, String name, String type, long parentId) {
    return new Account(
        id, name, type, parentId, EUR, null, OPENED, null, null, false, false, false);
  }

  private static Account personLeaf(long id, String name) {
    return new Account(id, name, ASSET, null, EUR, null, null, null, null, false, true, false);
  }

  @Test
  void movesPostedAccountWithoutTouchingItsOwnPostings() {
    when(accountRepository.findById(ACCOUNT_ID))
        .thenReturn(Optional.of(account(ACCOUNT_ID, "Giro", ASSET)));
    when(accountRepository.findById(PARENT_ID))
        .thenReturn(Optional.of(account(PARENT_ID, "Bank", ASSET)));
    when(accountRepository.hasPostings(PARENT_ID)).thenReturn(false);
    when(accountRepository.findSubtreeAccountIds(ACCOUNT_ID)).thenReturn(List.of(ACCOUNT_ID));

    reparenter.changeParent(ACCOUNT_ID, PARENT_ID);

    verify(accountRepository).updateParent(ACCOUNT_ID, PARENT_ID);
    // The moved account stays a leaf — its own posting history is never consulted.
    verify(accountRepository, never()).hasPostings(ACCOUNT_ID);
  }

  @Test
  void movesAccountToTopLevelWhenTheNewParentIsNull() {
    when(accountRepository.findById(ACCOUNT_ID))
        .thenReturn(Optional.of(child(ACCOUNT_ID, "Giro", ASSET, PARENT_ID)));

    reparenter.changeParent(ACCOUNT_ID, null);

    verify(accountRepository).updateParent(ACCOUNT_ID, null);
  }

  @Test
  void rejectsParentOfDifferentType() {
    when(accountRepository.findById(ACCOUNT_ID))
        .thenReturn(Optional.of(account(ACCOUNT_ID, "Giro", ASSET)));
    when(accountRepository.findById(PARENT_ID))
        .thenReturn(Optional.of(account(PARENT_ID, "Visa", "liability")));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> reparenter.changeParent(ACCOUNT_ID, PARENT_ID))
        .withMessageContaining("liability");

    verify(accountRepository, never()).updateParent(anyLong(), any());
  }

  @Test
  void rejectsParentThatHoldsPostings() {
    when(accountRepository.findById(ACCOUNT_ID))
        .thenReturn(Optional.of(account(ACCOUNT_ID, "Giro", ASSET)));
    when(accountRepository.findById(PARENT_ID))
        .thenReturn(Optional.of(account(PARENT_ID, "Cash", ASSET)));
    when(accountRepository.hasPostings(PARENT_ID)).thenReturn(true);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> reparenter.changeParent(ACCOUNT_ID, PARENT_ID))
        .withMessageContaining("leaves-only");

    verify(accountRepository, never()).updateParent(anyLong(), any());
  }

  @Test
  void rejectsAutoManagedLeafAsNewParent() {
    long personLeafId = 60L;
    when(accountRepository.findById(ACCOUNT_ID))
        .thenReturn(Optional.of(account(ACCOUNT_ID, "Giro", ASSET)));
    when(accountRepository.findById(personLeafId))
        .thenReturn(Optional.of(personLeaf(personLeafId, "personal.EUR")));
    when(accountRepository.hasPostings(personLeafId)).thenReturn(false);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> reparenter.changeParent(ACCOUNT_ID, personLeafId))
        .withMessageContaining("auto-managed leaf");

    verify(accountRepository, never()).updateParent(anyLong(), any());
  }

  @Test
  void rejectsClosedAccountAsNewParent() {
    Account closedParent =
        new Account(
            PARENT_ID,
            "Bank",
            ASSET,
            null,
            EUR,
            null,
            OPENED,
            LocalDate.of(2026, 8, 1),
            null,
            false,
            false,
            false);
    when(accountRepository.findById(ACCOUNT_ID))
        .thenReturn(Optional.of(account(ACCOUNT_ID, "Giro", ASSET)));
    when(accountRepository.findById(PARENT_ID)).thenReturn(Optional.of(closedParent));
    when(accountRepository.hasPostings(PARENT_ID)).thenReturn(false);

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> reparenter.changeParent(ACCOUNT_ID, PARENT_ID))
        .withMessageContaining("closed");

    verify(accountRepository, never()).updateParent(anyLong(), any());
  }

  @Test
  void rejectsMoveUnderAnyDescendantAtGrandchildDepth() {
    long grandchildId = 99L;
    when(accountRepository.findById(ACCOUNT_ID))
        .thenReturn(Optional.of(account(ACCOUNT_ID, "Bank", ASSET)));
    when(accountRepository.findById(grandchildId))
        .thenReturn(Optional.of(child(grandchildId, "Sub-sub", ASSET, PARENT_ID)));
    when(accountRepository.hasPostings(grandchildId)).thenReturn(false);
    // ACCOUNT_ID -> PARENT_ID (child) -> grandchildId: the subtree, walked to arbitrary depth.
    when(accountRepository.findSubtreeAccountIds(ACCOUNT_ID))
        .thenReturn(List.of(ACCOUNT_ID, PARENT_ID, grandchildId));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> reparenter.changeParent(ACCOUNT_ID, grandchildId))
        .withMessageContaining("cycle");

    verify(accountRepository, never()).updateParent(anyLong(), any());
  }

  @Test
  void rejectsMovingAccountUnderItself() {
    when(accountRepository.findById(ACCOUNT_ID))
        .thenReturn(Optional.of(account(ACCOUNT_ID, "Bank", ASSET)));
    when(accountRepository.hasPostings(ACCOUNT_ID)).thenReturn(false);
    when(accountRepository.findSubtreeAccountIds(ACCOUNT_ID)).thenReturn(List.of(ACCOUNT_ID));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> reparenter.changeParent(ACCOUNT_ID, ACCOUNT_ID))
        .withMessageContaining("cycle");

    verify(accountRepository, never()).updateParent(anyLong(), any());
  }

  @Test
  void refusesToMovePersonDebtLeaf() {
    when(accountRepository.findById(ACCOUNT_ID))
        .thenReturn(Optional.of(personLeaf(ACCOUNT_ID, "personal.EUR")));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> reparenter.changeParent(ACCOUNT_ID, PARENT_ID))
        .withMessageContaining("auto-managed");

    verify(accountRepository, never()).updateParent(anyLong(), any());
  }

  @Test
  void refusesToMoveCurrencyCategoryLeaf() {
    // A per-currency category leaf is income/expense — not a type the screen manages at all.
    Account eurLeaf =
        new Account(
            ACCOUNT_ID, "EUR", EXPENSE, PARENT_ID, EUR, null, null, null, null, true, false, false);
    when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(eurLeaf));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> reparenter.changeParent(ACCOUNT_ID, 5L))
        .withMessageContaining("not edited here");

    verify(accountRepository, never()).updateParent(anyLong(), any());
  }

  @Test
  void parentCandidatesExcludeSelfDescendantsAndOtherTypes() {
    Account self = account(ACCOUNT_ID, "Bank", ASSET);
    Account descendant = child(50L, "Checking", ASSET, ACCOUNT_ID);
    Account sibling = account(51L, "Savings", ASSET);
    Account wrongType = account(52L, "Visa", "liability");
    when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(self));
    when(accountRepository.findLiveByTypes(any()))
        .thenReturn(List.of(self, descendant, sibling, wrongType));
    when(accountRepository.findPostedAccountIds()).thenReturn(List.of());
    when(accountRepository.findParentAccountIds()).thenReturn(List.of(ACCOUNT_ID));
    when(accountRepository.findSubtreeAccountIds(ACCOUNT_ID)).thenReturn(List.of(ACCOUNT_ID, 50L));

    assertThat(reparenter.parentCandidatesFor(ACCOUNT_ID)).containsExactly(sibling);
  }
}
