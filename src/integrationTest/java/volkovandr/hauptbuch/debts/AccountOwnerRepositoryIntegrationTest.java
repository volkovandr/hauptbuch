package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository.AccountPersonName;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository.DeletedPersonLeaf;

/**
 * Integration tier (plan §1.5): repository row-mapping round-trips for {@link
 * AccountOwnerRepository}'s person-name lookup (register §2.6, plan stage 8c) and the soft-deleted
 * owner lookup (issue transaction-register-ui/23). Person leaves are provisioned through {@link
 * PersonProvisioningService} so the {@code account_owner} link the query joins is real. Each test
 * is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountOwnerRepositoryIntegrationTest {

  private static final String EUR = "EUR";

  @Autowired AccountOwnerRepository accountOwnerRepository;
  @Autowired PersonProvisioningService personProvisioningService;
  @Autowired PersonService personService;
  @Autowired JdbcClient jdbcClient;

  /** Provision a person's per-currency debt leaf and return its account id. */
  private long provisionLeaf(String name, String currency) {
    return personProvisioningService.ensureLeaf(name, currency, false).accountId();
  }

  /** The person id of the sole live person with this exact name. */
  private long personId(String name) {
    return ((PersonMatch.Live) personService.matchExact(name)).person().personId();
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

  @Test
  void findLiveAccountLinksExcludesLiveOwnersSoftDeletedLeaf() {
    // A merge retires a source person's emptied leaves; if that person is later revived, the stale
    // leaf id must not come back through here (issue transaction-register-ui/23).
    long maxEur = provisionLeaf("Max", EUR);
    long maxChf = provisionLeaf("Max", "CHF");
    long maxId = personId("Max");
    jdbcClient
        .sql("update account set deleted_at = now() where account_id = :id")
        .param("id", maxEur)
        .update();

    assertThat(accountOwnerRepository.findLiveAccountLinks())
        .filteredOn(link -> link.personId() == maxId)
        .extracting(AccountOwner::accountId)
        .containsExactly(maxChf);
  }

  @Test
  void findSoftDeletedPersonLeavesReturnsEachDeletedOwnersLiveLeavesNameThenCurrencyOrdered() {
    long bobChf = provisionLeaf("Bob", "CHF");
    long bobEur = provisionLeaf("Bob", EUR);
    provisionLeaf("Alice", EUR); // Alice stays live — excluded.
    long bobId = personId("Bob");
    personService.softDeleteIfZeroBalance(bobId);

    assertThat(accountOwnerRepository.findSoftDeletedPersonLeaves())
        .extracting(
            DeletedPersonLeaf::personId, DeletedPersonLeaf::name, DeletedPersonLeaf::accountId)
        .containsExactly(
            tuple(bobId, "Bob", bobChf), tuple(bobId, "Bob", bobEur)); // currency-ordered
  }

  @Test
  void findSoftDeletedPersonLeavesExcludesLeavesWhoseOwnAccountRowIsSoftDeleted() {
    long carolEur = provisionLeaf("Carol", EUR);
    provisionLeaf("Carol", "CHF");
    personService.softDeleteIfZeroBalance(personId("Carol"));
    jdbcClient
        .sql("update account set deleted_at = now() where account_id = :id")
        .param("id", carolEur)
        .update();

    assertThat(accountOwnerRepository.findSoftDeletedPersonLeaves())
        .extracting(DeletedPersonLeaf::accountId)
        .doesNotContain(carolEur);
  }

  @Test
  void findLeafAccountIdReturnsThePersonsLeafInThatCurrency() {
    long maxEur = provisionLeaf("Max", EUR);
    long maxChf = provisionLeaf("Max", "CHF");
    long personId = ((PersonMatch.Live) personService.matchExact("Max")).person().personId();

    assertThat(accountOwnerRepository.findLeafAccountId(personId, EUR)).contains(maxEur);
    assertThat(accountOwnerRepository.findLeafAccountId(personId, "CHF")).contains(maxChf);
  }

  @Test
  void findLeafAccountIdIsEmptyForCurrencyThePersonDoesNotHold() {
    provisionLeaf("Max", EUR); // Max holds a EUR leaf but no USD one.
    long personId = ((PersonMatch.Live) personService.matchExact("Max")).person().personId();

    assertThat(accountOwnerRepository.findLeafAccountId(personId, "USD")).isEmpty();
  }

  @Test
  void findLeavesByPersonIdReturnsEveryLeafWithItsCurrency() {
    long maxEur = provisionLeaf("Max", EUR);
    long maxChf = provisionLeaf("Max", "CHF");
    long personId = ((PersonMatch.Live) personService.matchExact("Max")).person().personId();

    assertThat(accountOwnerRepository.findLeavesByPersonId(personId))
        .extracting(PersonLeaf::accountId, PersonLeaf::currencyCode)
        .containsExactlyInAnyOrder(tuple(maxEur, EUR), tuple(maxChf, "CHF"));
  }

  @Test
  void findLeavesByPersonIdIsEmptyForPersonWithNoLeaves() {
    Person loner = personService.create("Loner");

    assertThat(accountOwnerRepository.findLeavesByPersonId(loner.personId())).isEmpty();
  }
}
