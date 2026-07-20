package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;

class PersonAccountResolutionServiceTest {

  private final AccountService accountService = mock();
  private final PersonService personService = mock();
  private final AccountOwnerRepository accountOwnerRepository = mock();
  private final PersonAccountResolutionService service =
      new PersonAccountResolutionService(accountService, personService, accountOwnerRepository);

  private static Account account(long id, String name, String currencyCode) {
    return new Account(id, name, "asset", null, currencyCode, null, null, null, null, false);
  }

  @Test
  void resolvesRealAccountByName() {
    Account cash = account(1L, "Cash", "EUR");
    when(accountService.findOwnAccountByName("Cash")).thenReturn(Optional.of(cash));

    assertThat(service.resolve("Cash")).isEqualTo(cash);
  }

  @Test
  void resolvesPersonWithExactlyOneCurrencyLeaf() {
    when(accountService.findOwnAccountByName("Max")).thenReturn(Optional.empty());
    Person max = new Person(1L, "Max", null);
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.Live(max));
    Account leaf = account(10L, "personal.EUR", "EUR");
    when(accountOwnerRepository.findAccountIdsByPersonId(1L)).thenReturn(List.of(10L));
    when(accountService.findById(10L)).thenReturn(Optional.of(leaf));

    assertThat(service.resolve("Max")).isEqualTo(leaf);
  }

  @Test
  void resolvesPersonWithMultipleLeavesWhenDisambiguated() {
    when(accountService.findOwnAccountByName("Max")).thenReturn(Optional.empty());
    Person max = new Person(1L, "Max", null);
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.Live(max));
    Account eurLeaf = account(10L, "personal.EUR", "EUR");
    Account chfLeaf = account(11L, "personal.CHF", "CHF");
    when(accountOwnerRepository.findAccountIdsByPersonId(1L)).thenReturn(List.of(10L, 11L));
    when(accountService.findById(10L)).thenReturn(Optional.of(eurLeaf));
    when(accountService.findById(11L)).thenReturn(Optional.of(chfLeaf));

    assertThat(service.resolve("Max (CHF)")).isEqualTo(chfLeaf);
  }

  @Test
  void rejectsPersonWithMultipleLeavesWhenNotDisambiguated() {
    when(accountService.findOwnAccountByName("Max")).thenReturn(Optional.empty());
    Person max = new Person(1L, "Max", null);
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.Live(max));
    Account eurLeaf = account(10L, "personal.EUR", "EUR");
    Account chfLeaf = account(11L, "personal.CHF", "CHF");
    when(accountOwnerRepository.findAccountIdsByPersonId(1L)).thenReturn(List.of(10L, 11L));
    when(accountService.findById(10L)).thenReturn(Optional.of(eurLeaf));
    when(accountService.findById(11L)).thenReturn(Optional.of(chfLeaf));

    assertThatThrownBy(() -> service.resolve("Max"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than one currency");
  }

  @Test
  void rejectsPersonWithNoLeafYet() {
    when(accountService.findOwnAccountByName("Max")).thenReturn(Optional.empty());
    Person max = new Person(1L, "Max", null);
    when(personService.matchExact("Max")).thenReturn(new PersonMatch.Live(max));
    when(accountOwnerRepository.findAccountIdsByPersonId(1L)).thenReturn(List.of());

    assertThatThrownBy(() -> service.resolve("Max"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no debt account yet");
  }

  @Test
  void rejectsAnUnknownName() {
    when(accountService.findOwnAccountByName("Ghost")).thenReturn(Optional.empty());
    when(personService.matchExact("Ghost")).thenReturn(new PersonMatch.NotFound());

    assertThatThrownBy(() -> service.resolve("Ghost"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No account or person named");
  }

  @Test
  void rejectsDeletedOnlyMatch() {
    when(accountService.findOwnAccountByName("Max")).thenReturn(Optional.empty());
    when(personService.matchExact("Max"))
        .thenReturn(new PersonMatch.DeletedOnly(new Person(1L, "Max", null)));

    assertThatThrownBy(() -> service.resolve("Max")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnAmbiguousLiveMatch() {
    when(accountService.findOwnAccountByName("Max")).thenReturn(Optional.empty());
    when(personService.matchExact("Max"))
        .thenReturn(
            new PersonMatch.Ambiguous(
                List.of(new Person(1L, "Max", null), new Person(2L, "Max", null))));

    assertThatThrownBy(() -> service.resolve("Max")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankText() {
    assertThatThrownBy(() -> service.resolve("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.resolve(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
