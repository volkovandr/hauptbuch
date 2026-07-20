package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;
import volkovandr.hauptbuch.debts.repository.PersonRepository;

class PersonProvisioningServiceTest {

  private final PersonService personService = mock();
  private final PersonRepository personRepository = mock();
  private final AccountOwnerRepository accountOwnerRepository = mock();
  private final AccountService accountService = mock();
  private final PersonProvisioningService service =
      new PersonProvisioningService(
          personService, personRepository, accountOwnerRepository, accountService);

  private static Account leaf(long accountId, String currencyCode) {
    return new Account(
        accountId,
        "personal." + currencyCode,
        "asset",
        null,
        currencyCode,
        null,
        null,
        null,
        null,
        false,
        true);
  }

  @Test
  void reusesTheExistingLeafOfLivePerson() {
    Person max = new Person(1L, "Max", null);
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.Live(max));
    when(accountOwnerRepository.findAccountIdsByPersonId(1L)).thenReturn(List.of(10L));
    when(accountService.findById(10L)).thenReturn(Optional.of(leaf(10L, "EUR")));

    Account result = service.ensureLeaf("Max", "EUR", false);

    assertThat(result.accountId()).isEqualTo(10L);
    verify(personRepository, never()).insert(anyString());
  }

  @Test
  void createsLeafForLivePersonWithNoneInThatCurrencyYet() {
    Person max = new Person(1L, "Max", null);
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.Live(max));
    when(accountOwnerRepository.findAccountIdsByPersonId(1L)).thenReturn(List.of());
    when(accountService.insertPersonLeaf("personal.EUR", "EUR")).thenReturn(leaf(20L, "EUR"));

    Account result = service.ensureLeaf("Max", "EUR", false);

    assertThat(result.accountId()).isEqualTo(20L);
    verify(accountOwnerRepository).insert(20L, 1L);
  }

  @Test
  void createsBrandNewPersonAndLeafWhenNoneExists() {
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.NotFound());
    when(personRepository.insert("Max")).thenReturn(new Person(2L, "Max", null));
    when(accountOwnerRepository.findAccountIdsByPersonId(2L)).thenReturn(List.of());
    when(accountService.insertPersonLeaf("personal.EUR", "EUR")).thenReturn(leaf(21L, "EUR"));

    Account result = service.ensureLeaf("Max", "EUR", false);

    assertThat(result.accountId()).isEqualTo(21L);
    verify(personRepository).insert("Max");
    verify(accountOwnerRepository).insert(21L, 2L);
  }

  @Test
  void revivesTheSoftDeletedPersonWhenReviveIsTrue() {
    Person deleted = new Person(3L, "Max", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.DeletedOnly(deleted));
    when(personRepository.revive(3L)).thenReturn(new Person(3L, "Max", null));
    when(accountOwnerRepository.findAccountIdsByPersonId(3L)).thenReturn(List.of());
    when(accountService.insertPersonLeaf("personal.EUR", "EUR")).thenReturn(leaf(30L, "EUR"));

    Account result = service.ensureLeaf("Max", "EUR", true);

    assertThat(result.accountId()).isEqualTo(30L);
    verify(personRepository).revive(3L);
    verify(personRepository, never()).insert("Max");
  }

  @Test
  void createsDistinctNewPersonWhenReviveIsFalseDespiteDeletedMatch() {
    Person deleted = new Person(3L, "Max", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.DeletedOnly(deleted));
    when(personRepository.insert("Max")).thenReturn(new Person(4L, "Max", null));
    when(accountOwnerRepository.findAccountIdsByPersonId(4L)).thenReturn(List.of());
    when(accountService.insertPersonLeaf("personal.EUR", "EUR")).thenReturn(leaf(31L, "EUR"));

    Account result = service.ensureLeaf("Max", "EUR", false);

    assertThat(result.accountId()).isEqualTo(31L);
    verify(personRepository, never()).revive(3L);
    verify(personRepository).insert("Max");
  }

  @Test
  void rejectsAnAmbiguousName() {
    Person max1 = new Person(1L, "Max", null);
    Person max2 = new Person(2L, "Max", null);
    when(personService.matchExact("Max"))
        .thenReturn(new PersonMatch.Ambiguous(List.of(max1, max2)));

    assertThatThrownBy(() -> service.ensureLeaf("Max", "EUR", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("More than one");
  }

  @Test
  void rejectsBlankName() {
    assertThatThrownBy(() -> service.ensureLeaf("", "EUR", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.ensureLeaf(null, "EUR", false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
