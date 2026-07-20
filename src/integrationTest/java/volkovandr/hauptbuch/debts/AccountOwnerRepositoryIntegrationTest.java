package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository.AccountPersonName;

/**
 * Integration tier (plan §1.5): repository row-mapping round-trips for {@link
 * AccountOwnerRepository}'s person-name lookup (register §2.6, plan stage 8c). Person leaves are
 * provisioned through {@link PersonProvisioningService} so the {@code account_owner} link the query
 * joins is real. Each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountOwnerRepositoryIntegrationTest {

  private static final String EUR = "EUR";

  @Autowired AccountOwnerRepository accountOwnerRepository;
  @Autowired PersonProvisioningService personProvisioningService;
  @Autowired PersonService personService;

  /** Provision a person's per-currency debt leaf and return its account id. */
  private long provisionLeaf(String name, String currency) {
    return personProvisioningService.ensureLeaf(name, currency, false).accountId();
  }

  @Test
  void findPersonNamesByAccountIdsKeysEachLeafToItsOwner() {
    long max = provisionLeaf("Max", EUR);
    long alice = provisionLeaf("Alice", EUR);

    List<AccountPersonName> names =
        // 999_999 is not a person leaf (no account_owner row) → it simply has no result.
        accountOwnerRepository.findPersonNamesByAccountIds(List.of(max, alice, 999_999L));

    assertThat(names)
        .extracting(AccountPersonName::accountId, AccountPersonName::name)
        .containsExactlyInAnyOrder(tuple(max, "Max"), tuple(alice, "Alice"));
  }

  @Test
  void findPersonNamesByAccountIdsResolvesSoftDeletedPerson() {
    // An old transaction's person leg must still display sensibly even after the person is
    // soft-deleted (a display lookup, not a liveness check). A freshly provisioned leaf is
    // zero-balance, so the soft-delete guard permits it.
    long bob = provisionLeaf("Bob", EUR);
    Person bobPerson = ((PersonMatch.Live) personService.matchExact("Bob")).person();
    personService.softDeleteIfZeroBalance(bobPerson.personId());

    List<AccountPersonName> names =
        accountOwnerRepository.findPersonNamesByAccountIds(List.of(bob));

    assertThat(names).extracting(AccountPersonName::name).containsExactly("Bob");
  }

  @Test
  void findPersonNamesByAccountIdsShortCircuitsOnEmptyInput() {
    assertThat(accountOwnerRepository.findPersonNamesByAccountIds(List.of())).isEmpty();
  }

  @Test
  void findLiveAccountLinksReturnsEachLivePersonsLeafKeyedToThem() {
    long maxEur = provisionLeaf("Max", EUR);
    long maxChf = provisionLeaf("Max", "CHF");
    long personId = ((PersonMatch.Live) personService.matchExact("Max")).person().personId();

    assertThat(accountOwnerRepository.findLiveAccountLinks())
        .filteredOn(link -> link.personId() == personId)
        .extracting(AccountOwner::accountId)
        .containsExactlyInAnyOrder(maxEur, maxChf);
  }

  @Test
  void findLiveAccountLinksExcludesSoftDeletedPersonsLeaves() {
    long aliceEur = provisionLeaf("Alice", EUR);
    // A freshly provisioned leaf is zero-balance, so the soft-delete guard permits it.
    long aliceId = ((PersonMatch.Live) personService.matchExact("Alice")).person().personId();
    personService.softDeleteIfZeroBalance(aliceId);

    assertThat(accountOwnerRepository.findLiveAccountLinks())
        .extracting(AccountOwner::accountId)
        .doesNotContain(aliceEur);
  }
}
