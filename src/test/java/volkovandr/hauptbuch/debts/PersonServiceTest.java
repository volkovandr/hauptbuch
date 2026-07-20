package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;
import volkovandr.hauptbuch.debts.repository.PersonRepository;

class PersonServiceTest {

  private final PersonRepository personRepository = mock();
  private final AccountOwnerRepository accountOwnerRepository = mock();
  private final PersonService service = new PersonService(personRepository, accountOwnerRepository);

  @Test
  void createInsertsPerson() {
    Person created = new Person(1L, "Max", null);
    when(personRepository.insert("Max")).thenReturn(created);

    Person result = service.create("Max");

    assertThat(result).isEqualTo(created);
    verify(personRepository).insert("Max");
  }

  @Test
  void createStripsWhitespace() {
    Person created = new Person(1L, "Alice", null);
    when(personRepository.insert("Alice")).thenReturn(created);

    service.create("  Alice  ");

    verify(personRepository).insert("Alice");
  }

  @Test
  void createRejectsBlankName() {
    assertThatThrownBy(() -> service.create(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be blank");

    assertThatThrownBy(() -> service.create("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be blank");

    assertThatThrownBy(() -> service.create(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be blank");
  }

  @Test
  void renameUpdatesPersonName() {
    when(personRepository.updateName(1L, "Bob")).thenReturn(new Person(1L, "Bob", null));

    Person result = service.rename(1L, "Bob");

    assertThat(result.name()).isEqualTo("Bob");
    verify(personRepository).updateName(1L, "Bob");
  }

  @Test
  void renameRejectsBlankName() {
    assertThatThrownBy(() -> service.rename(1L, ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be blank");
  }

  @Test
  void rejectsNameBeginningWithReservedSigil() {
    // A person named "for Max" would be indistinguishable from the sigil "for" applied to "Max"
    // (data-model §7, plan stage 8b.1) — refused at both entry points into the name.
    assertThatThrownBy(() -> service.create("by Max"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot begin with");

    assertThatThrownBy(() -> service.rename(1L, "For Kids"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot begin with");

    verify(personRepository, never()).insert(anyString());
    verify(personRepository, never()).updateName(anyLong(), anyString());
  }

  @Test
  void softDeleteIfZeroBalanceDeletesWhenBalanceIsZero() {
    Person person = new Person(1L, "Max", null);
    when(personRepository.findById(1L)).thenReturn(Optional.of(person));
    when(accountOwnerRepository.findPersonCurrencyBalances(1L))
        .thenReturn(
            List.of(
                new AccountOwnerRepository.PersonCurrencyBalance(1L, "EUR", BigDecimal.ZERO),
                new AccountOwnerRepository.PersonCurrencyBalance(1L, "CHF", BigDecimal.ZERO)));

    service.softDeleteIfZeroBalance(1L);

    verify(personRepository).softDelete(1L);
  }

  @Test
  void softDeleteIfZeroBalanceThrowsWhenBalanceNonZero() {
    Person person = new Person(1L, "Max", null);
    when(personRepository.findById(1L)).thenReturn(Optional.of(person));
    when(accountOwnerRepository.findPersonCurrencyBalances(1L))
        .thenReturn(
            List.of(
                new AccountOwnerRepository.PersonCurrencyBalance(
                    1L, "EUR", new BigDecimal("10.00")),
                new AccountOwnerRepository.PersonCurrencyBalance(1L, "CHF", BigDecimal.ZERO)));

    assertThatThrownBy(() -> service.softDeleteIfZeroBalance(1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot soft-delete")
        .hasMessageContaining("EUR");
  }

  @Test
  void softDeleteIfZeroBalanceThrowsWhenPersonNotFound() {
    when(personRepository.findById(1L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.softDeleteIfZeroBalance(1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  @Test
  void findByIdReturnsPerson() {
    Person person = new Person(1L, "Max", null);
    when(personRepository.findById(1L)).thenReturn(Optional.of(person));

    Optional<Person> result = service.findById(1L);

    assertThat(result).contains(person);
  }

  @Test
  void findByIdReturnsEmptyWhenNotFound() {
    when(personRepository.findById(1L)).thenReturn(Optional.empty());

    Optional<Person> result = service.findById(1L);

    assertThat(result).isEmpty();
  }

  @Test
  void findAllLiveReturnsList() {
    List<Person> persons = List.of(new Person(1L, "Alice", null), new Person(2L, "Bob", null));
    when(personRepository.findAllLive()).thenReturn(persons);

    List<Person> result = service.findAllLive();

    assertThat(result).isEqualTo(persons);
  }

  @Test
  void findByNameContainingReturnsMatches() {
    List<Person> persons = List.of(new Person(1L, "Alice", null));
    when(personRepository.findByNameContaining("Ali")).thenReturn(persons);

    List<Person> result = service.findByNameContaining("Ali");

    assertThat(result).isEqualTo(persons);
  }

  @Test
  void findByNameContainingWithBlankReturnsAll() {
    List<Person> persons = List.of(new Person(1L, "Alice", null), new Person(2L, "Bob", null));
    when(personRepository.findAllLive()).thenReturn(persons);

    List<Person> result = service.findByNameContaining("");

    assertThat(result).isEqualTo(persons);
    verify(personRepository).findAllLive();
  }

  @Test
  void matchExactReturnsLiveForSoleLiveMatch() {
    Person max = new Person(1L, "Max", null);
    when(personRepository.findAllByNameExact("Max")).thenReturn(List.of(max));

    PersonMatch result = service.matchExact("Max");

    assertThat(result).isEqualTo(new PersonMatch.Live(max));
  }

  @Test
  void matchExactReturnsNotFoundWhenNoRowsAtAll() {
    when(personRepository.findAllByNameExact("Max")).thenReturn(List.of());

    PersonMatch result = service.matchExact("Max");

    assertThat(result).isEqualTo(new PersonMatch.NotFound());
  }

  @Test
  void matchExactReturnsDeletedOnlyWhenOnlySoftDeletedRowMatches() {
    OffsetDateTime deletedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z");
    Person max = new Person(1L, "Max", deletedAt);
    when(personRepository.findAllByNameExact("Max")).thenReturn(List.of(max));

    PersonMatch result = service.matchExact("Max");

    assertThat(result).isEqualTo(new PersonMatch.DeletedOnly(max));
  }

  @Test
  void matchExactReturnsMostRecentlyDeletedAmongSeveralDeletedRows() {
    Person older = new Person(1L, "Max", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    Person newer = new Person(2L, "Max", OffsetDateTime.parse("2026-06-01T00:00:00Z"));
    when(personRepository.findAllByNameExact("Max")).thenReturn(List.of(older, newer));

    PersonMatch result = service.matchExact("Max");

    assertThat(result).isEqualTo(new PersonMatch.DeletedOnly(newer));
  }

  @Test
  void matchExactReturnsAmbiguousForMultipleLiveMatches() {
    Person max1 = new Person(1L, "Max", null);
    Person max2 = new Person(2L, "Max", null);
    when(personRepository.findAllByNameExact("Max")).thenReturn(List.of(max1, max2));

    PersonMatch result = service.matchExact("Max");

    assertThat(result).isInstanceOf(PersonMatch.Ambiguous.class);
    assertThat(((PersonMatch.Ambiguous) result).matches()).containsExactlyInAnyOrder(max1, max2);
  }

  @Test
  void matchExactIgnoresDeletedDuplicateWhenLiveMatchExists() {
    Person live = new Person(1L, "Max", null);
    Person deleted = new Person(2L, "Max", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    when(personRepository.findAllByNameExact("Max")).thenReturn(List.of(live, deleted));

    PersonMatch result = service.matchExact("Max");

    assertThat(result).isEqualTo(new PersonMatch.Live(live));
  }

  @Test
  void matchExactRejectsBlankName() {
    assertThatThrownBy(() -> service.matchExact("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void personNameForAccountReturnsTheOwningPersonsName() {
    when(accountOwnerRepository.findByAccountId(10L))
        .thenReturn(Optional.of(new AccountOwner(1L, 10L, 5L)));
    when(personRepository.findByIdIncludingDeleted(5L))
        .thenReturn(Optional.of(new Person(5L, "Max", null)));

    assertThat(service.personNameForAccount(10L)).contains("Max");
  }

  @Test
  void personNameForAccountResolvesEvenSoftDeletedPerson() {
    OffsetDateTime deletedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    when(accountOwnerRepository.findByAccountId(10L))
        .thenReturn(Optional.of(new AccountOwner(1L, 10L, 5L)));
    when(personRepository.findByIdIncludingDeleted(5L))
        .thenReturn(Optional.of(new Person(5L, "Max", deletedAt)));

    assertThat(service.personNameForAccount(10L)).contains("Max");
  }

  @Test
  void personNameForAccountReturnsEmptyForAnUnownedAccount() {
    when(accountOwnerRepository.findByAccountId(10L)).thenReturn(Optional.empty());

    assertThat(service.personNameForAccount(10L)).isEmpty();
  }

  @Test
  void personNamesForAccountsKeysNamesByAccountId() {
    when(accountOwnerRepository.findPersonNamesByAccountIds(List.of(10L, 11L, 12L)))
        .thenReturn(
            List.of(
                new AccountOwnerRepository.AccountPersonName(10L, "Max"),
                new AccountOwnerRepository.AccountPersonName(11L, "Alice")));

    // 12L is an ordinary account (no owner) → simply absent from the map.
    assertThat(service.personNamesForAccounts(List.of(10L, 11L, 12L)))
        .containsOnly(entry(10L, "Max"), entry(11L, "Alice"));
  }

  @Test
  void personNamesForAccountsShortCircuitsOnEmptyInput() {
    assertThat(service.personNamesForAccounts(List.of())).isEmpty();
    verify(accountOwnerRepository, never()).findPersonNamesByAccountIds(any());
  }

  @Test
  void balanceSummariesGroupsNonZeroBalancesAndLeavesByLivePerson() {
    when(personRepository.findAllLive())
        .thenReturn(List.of(new Person(1L, "Alice", null), new Person(2L, "Bob", null)));
    when(accountOwnerRepository.findAllPersonCurrencyBalances())
        .thenReturn(
            List.of(
                // Alice: CHF then EUR (the query orders by currency), both non-zero.
                new AccountOwnerRepository.PersonCurrencyBalance(1L, "CHF", new BigDecimal("5.00")),
                new AccountOwnerRepository.PersonCurrencyBalance(
                    1L, "EUR", new BigDecimal("-10.00")),
                // Bob: a currency that nets to zero — filtered out, so Bob is settled.
                new AccountOwnerRepository.PersonCurrencyBalance(2L, "EUR", BigDecimal.ZERO)));
    when(accountOwnerRepository.findLiveAccountLinks())
        .thenReturn(
            List.of(
                new AccountOwner(10L, 100L, 1L),
                new AccountOwner(11L, 101L, 1L),
                new AccountOwner(12L, 102L, 2L)));

    List<PersonBalanceSummary> result = service.balanceSummaries();

    assertThat(result).hasSize(2);
    PersonBalanceSummary alice = result.get(0);
    assertThat(alice.name()).isEqualTo("Alice");
    assertThat(alice.balances())
        .containsExactly(
            new CurrencyBalance("CHF", new BigDecimal("5.00")),
            new CurrencyBalance("EUR", new BigDecimal("-10.00")));
    assertThat(alice.accountIds()).containsExactly(100L, 101L);

    PersonBalanceSummary bob = result.get(1);
    assertThat(bob.name()).isEqualTo("Bob");
    assertThat(bob.balances()).isEmpty();
    assertThat(bob.accountIds()).containsExactly(102L);
  }

  @Test
  void balanceSummariesHandlesPersonWithNoLeavesOrBalances() {
    when(personRepository.findAllLive()).thenReturn(List.of(new Person(1L, "Newcomer", null)));
    when(accountOwnerRepository.findAllPersonCurrencyBalances()).thenReturn(List.of());
    when(accountOwnerRepository.findLiveAccountLinks()).thenReturn(List.of());

    List<PersonBalanceSummary> result = service.balanceSummaries();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).balances()).isEmpty();
    assertThat(result.get(0).accountIds()).isEmpty();
  }
}
