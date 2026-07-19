package volkovandr.hauptbuch.debts;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;
import volkovandr.hauptbuch.debts.repository.PersonRepository;

/**
 * Service for person lifecycle (stage 8a). Owns create / rename / soft-delete operations with their
 * invariants. Auto-provisioning (ensure person + leaf + link) is separate, in {@link
 * PersonProvisioningService}.
 */
@Service
@Transactional
public class PersonService {

  private final PersonRepository personRepository;
  private final AccountOwnerRepository accountOwnerRepository;

  PersonService(PersonRepository personRepository, AccountOwnerRepository accountOwnerRepository) {
    this.personRepository = personRepository;
    this.accountOwnerRepository = accountOwnerRepository;
  }

  /** Create a new person with the given name. */
  public Person create(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Person name cannot be blank");
    }
    return personRepository.insert(name.strip());
  }

  /** Rename a person. Works on both live and soft-deleted persons. */
  public Person rename(Long personId, String newName) {
    if (newName == null || newName.isBlank()) {
      throw new IllegalArgumentException("Person name cannot be blank");
    }
    return personRepository.updateName(personId, newName.strip());
  }

  /**
   * Soft-delete a live person, but only if all their per-currency accounts are zero-balance. A
   * non-zero person is removed only by merge (reassign their postings to another person).
   */
  public void softDeleteIfZeroBalance(Long personId) {
    Person person =
        personRepository
            .findById(personId)
            .orElseThrow(() -> new IllegalArgumentException("Person not found: " + personId));

    List<AccountOwnerRepository.PersonCurrencyBalance> balances =
        accountOwnerRepository.findPersonCurrencyBalances(personId);

    for (AccountOwnerRepository.PersonCurrencyBalance balance : balances) {
      if (balance.getSignedBalance().compareTo(BigDecimal.ZERO) != 0) {
        throw new IllegalStateException(
            "Cannot soft-delete person '"
                + person.name()
                + "' with non-zero balance in "
                + balance.getCurrencyCode());
      }
    }

    personRepository.softDelete(personId);
  }

  /** Fetch a live person by ID. Returns empty if the person does not exist or is soft-deleted. */
  public Optional<Person> findById(Long personId) {
    return personRepository.findById(personId);
  }

  /** Fetch all live persons, ordered by name. */
  public List<Person> findAllLive() {
    return personRepository.findAllLive();
  }

  /**
   * Fetch live persons matching a name fragment (substring search). Useful for pickers with
   * type-ahead.
   */
  public List<Person> findByNameContaining(String nameFragment) {
    if (nameFragment == null || nameFragment.isBlank()) {
      return findAllLive();
    }
    return personRepository.findByNameContaining(nameFragment.strip());
  }
}
