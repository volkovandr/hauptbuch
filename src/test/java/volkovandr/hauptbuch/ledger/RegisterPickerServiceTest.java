package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonBalanceSummary;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;

/**
 * Unit tier (plan §1.5): {@link RegisterPickerService} resolving each {@link RegisterPicker} to its
 * member account ids with the account, person and register repositories mocked. The one
 * SQL-resident collaborator — the activity lookup for {@link RegisterPicker#LAST_USED} — is covered
 * in {@code RegisterActivitySqlLogicTest}.
 */
@ExtendWith(MockitoExtension.class)
class RegisterPickerServiceTest {

  private static final String EUR = "EUR";
  private static final String ASSET = "asset";
  private static final List<String> OWN_TYPES = List.of("asset", "liability");

  @Mock private AccountService accountService;
  @Mock private PersonService personService;
  @Mock private RegisterRepository registerRepository;

  private RegisterPickerService pickerService;

  @BeforeEach
  void setUp() {
    pickerService = new RegisterPickerService(accountService, personService, registerRepository);
    lenient().when(personService.balanceSummaries()).thenReturn(List.of());
    lenient().when(personService.deletedPeople()).thenReturn(List.of());
    lenient()
        .when(registerRepository.findAccountIdsWithActivity(any(), any()))
        .thenReturn(List.of());
  }

  private static Account account(long id, String name, Long parentId, LocalDate closedAt) {
    return new Account(
        id, name, ASSET, parentId, EUR, 210, LocalDate.now(), closedAt, null, false, false, false);
  }

  private static Account personLeaf(long id, String name) {
    return new Account(
        id, name, ASSET, null, EUR, null, LocalDate.now(), null, null, false, true, false);
  }

  private static AccountNode node(Account account, int depth) {
    return new AccountNode(account, depth);
  }

  private void ownAccounts(AccountNode... nodes) {
    when(accountService.findLiveByTypesWithDepth(OWN_TYPES)).thenReturn(List.of(nodes));
  }

  @Test
  void openExcludesClosedAccountsAndPersonLeaves() {
    Account cash = account(10, "Cash", null, null);
    Account oldGiro = account(11, "Old Giro", null, LocalDate.now().minusYears(1));
    ownAccounts(node(cash, 0), node(oldGiro, 0), node(personLeaf(9, "personal.EUR"), 0));

    assertThat(pickerService.membership(RegisterPicker.OPEN, null, null)).containsExactly(10L);
  }

  @Test
  void closedListsOnlyClosedRealLeavesWhenNoPersonIsSoftDeleted() {
    Account cash = account(10, "Cash", null, null);
    Account oldGiro = account(11, "Old Giro", null, LocalDate.now().minusYears(1));
    ownAccounts(node(cash, 0), node(oldGiro, 0));

    assertThat(pickerService.membership(RegisterPicker.CLOSED, null, null)).containsExactly(11L);
  }

  @Test
  void closedAlsoListsTheLiveLeavesOfSoftDeletedPeople() {
    // A settled-then-deleted person keeps their live leaf (9); it is not a closed real account, so
    // it belongs to no other picker but CLOSED and ALL (issue transaction-register-ui/23).
    Account oldGiro = account(11, "Old Giro", null, LocalDate.now().minusYears(1));
    Account samLeaf = personLeaf(9, "personal.EUR");
    ownAccounts(node(oldGiro, 0), node(samLeaf, 0));
    when(personService.deletedPeople())
        .thenReturn(List.of(new PersonBalanceSummary(7L, "Sam", List.of(), List.of(9L))));

    assertThat(pickerService.membership(RegisterPicker.CLOSED, null, null))
        .containsExactly(11L, 9L); // closed real leaves first, then the deleted-person leaves
  }

  @Test
  void allIncludesOpenClosedAndPersonLeaves() {
    Account cash = account(10, "Cash", null, null);
    Account oldGiro = account(11, "Old Giro", null, LocalDate.now().minusYears(1));
    ownAccounts(node(cash, 0), node(oldGiro, 0), node(personLeaf(9, "personal.EUR"), 0));

    assertThat(pickerService.membership(RegisterPicker.ALL, null, null))
        .containsExactlyInAnyOrder(10L, 11L, 9L);
  }

  @Test
  void parentAccountsAreNeverMembers() {
    // A "Banks" parent with two real children: posting is leaves-only, so the parent has no rows —
    // it is a group toggle in the panel, never a filter target.
    Account banks = account(20, "Banks", null, null);
    Account giro = account(21, "Giro", 20L, null);
    Account savings = account(22, "Savings", 20L, null);
    ownAccounts(node(banks, 0), node(giro, 1), node(savings, 1));

    assertThat(pickerService.membership(RegisterPicker.OPEN, null, null))
        .containsExactly(21L, 22L)
        .doesNotContain(20L);
  }

  @Test
  void personsListsEveryLivePersonsLeavesInRosterOrder() {
    // balanceSummaries() is already name-ordered (Alice, Bob); membership flattens it as-is —
    // alphabetical, no unsettled-first re-sort (owner feedback 2026-09-07).
    when(personService.balanceSummaries())
        .thenReturn(
            List.of(
                new PersonBalanceSummary(1L, "Alice", List.of(), List.of(101L)),
                new PersonBalanceSummary(2L, "Bob", List.of(), List.of(201L, 202L))));

    assertThat(pickerService.membership(RegisterPicker.PERSONS, null, null))
        .containsExactly(101L, 201L, 202L);
  }

  @Test
  void lastUsedExcludesClosedRealAccountsEvenWithRecentActivity() {
    Account cash = account(10, "Cash", null, null);
    Account oldGiro = account(11, "Old Giro", null, LocalDate.now().minusYears(1));
    ownAccounts(node(cash, 0), node(oldGiro, 0));
    when(registerRepository.findAccountIdsWithActivity(any(), any())).thenReturn(List.of(10L, 11L));

    assertThat(pickerService.membership(RegisterPicker.LAST_USED, null, null))
        .containsExactly(10L)
        .doesNotContain(11L);
  }

  @Test
  void lastUsedIsOwnLeavesIntersectedWithActivityAndKeepsPersonLeaves() {
    Account cash = account(10, "Cash", null, null);
    Account giro = account(11, "Giro", null, null);
    Account maxLeaf = personLeaf(9, "personal.EUR");
    ownAccounts(node(cash, 0), node(giro, 0), node(maxLeaf, 0));
    when(registerRepository.findAccountIdsWithActivity(any(), any()))
        .thenReturn(List.of(9L, 10L)); // Giro dormant

    assertThat(pickerService.membership(RegisterPicker.LAST_USED, null, null))
        .containsExactly(10L, 9L); // ownLeaves order preserved: Cash, then the person leaf
  }

  @Test
  void lastUsedExcludesSoftDeletedPeoplesLeavesEvenWithRecentActivity() {
    // Sam was settled then deleted; the settling transactions are recent, so the leaf shows
    // activity — but Last used stays live-only (issue transaction-register-ui/23).
    Account cash = account(10, "Cash", null, null);
    Account samLeaf = personLeaf(9, "personal.EUR");
    ownAccounts(node(cash, 0), node(samLeaf, 0));
    when(registerRepository.findAccountIdsWithActivity(any(), any())).thenReturn(List.of(9L, 10L));
    when(personService.deletedPeople())
        .thenReturn(List.of(new PersonBalanceSummary(7L, "Sam", List.of(), List.of(9L))));

    assertThat(pickerService.membership(RegisterPicker.LAST_USED, null, null))
        .containsExactly(10L)
        .doesNotContain(9L);
  }

  @Test
  void personGroupsForAllMergesLiveAndDeletedPeopleAlphabeticallyByName() {
    when(personService.balanceSummaries())
        .thenReturn(
            List.of(
                new PersonBalanceSummary(1L, "Ana", List.of(), List.of(101L)),
                new PersonBalanceSummary(2L, "Cody", List.of(), List.of(102L))));
    when(personService.deletedPeople())
        .thenReturn(List.of(new PersonBalanceSummary(3L, "Bea", List.of(), List.of(103L))));

    assertThat(pickerService.personGroups(RegisterPicker.ALL))
        .extracting(
            RegisterPickerService.PersonGroup::name, RegisterPickerService.PersonGroup::deleted)
        .containsExactly(tuple("Ana", false), tuple("Bea", true), tuple("Cody", false));
  }

  @Test
  void personGroupsForClosedIsSoftDeletedPeopleOnly() {
    when(personService.deletedPeople())
        .thenReturn(List.of(new PersonBalanceSummary(3L, "Bea", List.of(), List.of(103L))));

    assertThat(pickerService.personGroups(RegisterPicker.CLOSED))
        .extracting(RegisterPickerService.PersonGroup::name)
        .containsExactly("Bea");
  }

  @Test
  void resolveSelectionKeepsPartialTicksIntersectedWithMembership() {
    Account cash = account(10, "Cash", null, null);
    Account giro = account(11, "Giro", null, null);
    ownAccounts(node(cash, 0), node(giro, 0));

    // Submitted 10 and a stale 99: 99 is not in OPEN's membership and is dropped.
    assertThat(pickerService.resolveSelection(RegisterPicker.OPEN, List.of(10L, 99L), null, null))
        .containsExactly(10L);
  }

  @Test
  void resolveSelectionCollapsesEveryMemberTickedBackToTheWholePicker() {
    Account cash = account(10, "Cash", null, null);
    Account giro = account(11, "Giro", null, null);
    ownAccounts(node(cash, 0), node(giro, 0));

    // Every member ticked → empty, so the server re-resolves and the dock won't serialise it.
    assertThat(pickerService.resolveSelection(RegisterPicker.OPEN, List.of(10L, 11L), null, null))
        .isEmpty();
  }

  @Test
  void resolveSelectionOfNothingIsEmpty() {
    assertThat(pickerService.resolveSelection(RegisterPicker.OPEN, List.of(), null, null))
        .isEmpty();
  }

  @Test
  void lastUsedResolvesAgainstTheAppliedDateRange() {
    ownAccounts(node(account(10, "Cash", null, null), 0));
    LocalDate from = LocalDate.of(2026, 1, 1);
    LocalDate to = LocalDate.of(2026, 6, 30);

    pickerService.membership(RegisterPicker.LAST_USED, from, to);

    verify(registerRepository).findAccountIdsWithActivity(from, to);
  }
}
