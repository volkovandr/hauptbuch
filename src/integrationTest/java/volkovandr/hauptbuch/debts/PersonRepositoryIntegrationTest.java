package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.debts.repository.PersonRepository;

/**
 * Integration tier (plan §1.5): repository row-mapping round-trips for {@link PersonRepository}.
 * Flyway migrations apply; the table is live. Each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PersonRepositoryIntegrationTest {

  @Autowired PersonRepository personRepository;

  @Test
  void insertAndFindByIdRoundTrip() {
    Person inserted = personRepository.insert("AliceIt");

    assertThat(inserted.personId()).isNotNull();
    assertThat(inserted.name()).isEqualTo("AliceIt");
    assertThat(inserted.deletedAt()).isNull();

    Optional<Person> found = personRepository.findById(inserted.personId());

    assertThat(found).contains(inserted);
  }

  @Test
  void findByIdReturnsEmptyWhenNotFound() {
    Optional<Person> found = personRepository.findById(999L);

    assertThat(found).isEmpty();
  }

  @Test
  void findAllLiveReturnsList() {
    personRepository.insert("AliceIt");
    personRepository.insert("CharlieIt");
    Person bob = personRepository.insert("BobIt");
    personRepository.softDelete(bob.personId());

    List<Person> live = personRepository.findAllLive();

    assertThat(live).extracting(Person::name).containsExactlyInAnyOrder("AliceIt", "CharlieIt");
  }

  @Test
  void findAllLiveOrderedByName() {
    personRepository.insert("Charlie");
    personRepository.insert("Alice");
    personRepository.insert("Bob");

    List<Person> live = personRepository.findAllLive();

    assertThat(live).extracting(Person::name).containsExactly("Alice", "Bob", "Charlie");
  }

  @Test
  void findByNameContainingMatchesSubstring() {
    personRepository.insert("AliceIt");
    personRepository.insert("AlexIt");
    personRepository.insert("BobIt");

    List<Person> found = personRepository.findByNameContaining("Al");

    assertThat(found).extracting(Person::name).containsExactlyInAnyOrder("AliceIt", "AlexIt");
  }

  @Test
  void findByNameContainingCaseInsensitive() {
    personRepository.insert("AliceIt");

    List<Person> found = personRepository.findByNameContaining("alice");

    assertThat(found).extracting(Person::name).contains("AliceIt");
  }

  @Test
  void findByNameContainingExcludesDeleted() {
    Person alice = personRepository.insert("AliceIt");
    personRepository.insert("BobIt");
    personRepository.softDelete(alice.personId());

    List<Person> found = personRepository.findByNameContaining("Al");

    assertThat(found).isEmpty();
  }

  @Test
  void findByNameExactReturnsLivePerson() {
    Person inserted = personRepository.insert("AliceIt");

    Optional<Person> found = personRepository.findByNameExact("AliceIt");

    assertThat(found).contains(inserted);
  }

  @Test
  void findByNameExactIgnoresDeleted() {
    Person alice = personRepository.insert("AliceIt");
    personRepository.softDelete(alice.personId());

    Optional<Person> found = personRepository.findByNameExact("AliceIt");

    assertThat(found).isEmpty();
  }

  @Test
  void findByNameExactIncludingDeletedFindsLivePerson() {
    Person alice = personRepository.insert("AliceIt");

    Optional<Person> found = personRepository.findByNameExactIncludingDeleted("AliceIt");

    assertThat(found).contains(alice);
  }

  @Test
  void findByNameExactIncludingDeletedFindsDeletedPersonIfNoLiveMatch() {
    Person alice = personRepository.insert("AliceIt");
    personRepository.softDelete(alice.personId());

    Optional<Person> found = personRepository.findByNameExactIncludingDeleted("AliceIt");

    assertThat(found).isPresent();
    assertThat(found.get().deletedAt()).isNotNull();
  }

  @Test
  void findByNameExactIncludingDeletedReturnsSoftDeletedWhenNoLiveExists() {
    Person alice1 = personRepository.insert("AliceIt");
    personRepository.softDelete(alice1.personId());

    // Insert a new person with the same name, delete it later.
    Person alice2 = personRepository.insert("AliceIt");
    personRepository.softDelete(alice2.personId());

    Optional<Person> found = personRepository.findByNameExactIncludingDeleted("AliceIt");

    // Should return *a* soft-deleted person (prefers live if it exists; among deleted,
    // the order might vary if deleted_at timestamps are identical, so just check it's
    // deleted and has the right name).
    assertThat(found).isPresent();
    assertThat(found.get().deletedAt()).isNotNull();
    assertThat(found.get().name()).isEqualTo("AliceIt");
  }

  @Test
  void updateNameChangesName() {
    Person alice = personRepository.insert("AliceIt");

    Person updated = personRepository.updateName(alice.personId(), "AliceSmirnova");

    assertThat(updated.name()).isEqualTo("AliceSmirnova");

    Optional<Person> fetched = personRepository.findById(alice.personId());
    assertThat(fetched).isPresent();
    assertThat(fetched.get().name()).isEqualTo("AliceSmirnova");
  }

  @Test
  void softDeleteSetsDeletedAt() {
    Person alice = personRepository.insert("AliceIt");

    personRepository.softDelete(alice.personId());

    Optional<Person> found = personRepository.findById(alice.personId());
    assertThat(found).isEmpty();
  }

  @Test
  void reviveClearsDeletedAt() {
    Person alice = personRepository.insert("AliceIt");
    personRepository.softDelete(alice.personId());

    Person revived = personRepository.revive(alice.personId());

    assertThat(revived.deletedAt()).isNull();

    Optional<Person> found = personRepository.findById(alice.personId());
    assertThat(found).contains(revived);
  }

  @Test
  void duplicateNamesAllowed() {
    Person alice1 = personRepository.insert("AliceIt");
    Person alice2 = personRepository.insert("AliceIt");

    assertThat(alice1.personId()).isNotEqualTo(alice2.personId());

    List<Person> allAlices = personRepository.findByNameContaining("AliceIt");
    assertThat(allAlices).hasSize(2);
  }
}
