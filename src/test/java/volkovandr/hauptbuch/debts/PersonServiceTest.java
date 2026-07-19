package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
}
