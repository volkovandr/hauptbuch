package volkovandr.hauptbuch.debts;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.ReservedNamePrefix;
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
    ReservedNamePrefix.check(name);
    return personRepository.insert(name.strip());
  }

  /** Rename a person. Works on both live and soft-deleted persons. */
  public Person rename(Long personId, String newName) {
    if (newName == null || newName.isBlank()) {
      throw new IllegalArgumentException("Person name cannot be blank");
    }
    ReservedNamePrefix.check(newName);
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

  /**
   * The current display name of the person who owns {@code accountId}, if any (register §3.3, plan
   * stage 8b) — used to pre-fill the entry dock's person sub-field when editing a transaction whose
   * funding leg turns out to be a person's debt account, so the user sees "Max", not the leaf's
   * cosmetic internal name. Resolves even a since-soft-deleted person (rename/lifecycle never moves
   * ids, and an old transaction must still display sensibly), so this is a display lookup, not a
   * liveness check.
   */
  public Optional<String> personNameForAccount(long accountId) {
    return accountOwnerRepository
        .findByAccountId(accountId)
        .flatMap(owner -> personRepository.findByIdIncludingDeleted(owner.personId()))
        .map(Person::name);
  }

  /**
   * The one currency this person already carries a debt leaf in, when there is exactly one
   * (register §3.5, plan stage 8b.1) — the <em>transaction currency</em>'s default when the funding
   * leg is a person and there is therefore no account to inherit a currency from. Empty when the
   * name matches no live person, when they have no leaf yet (a brand-new person), or when they have
   * leaves in more than one currency: all three are cases the caller must fall back to base for,
   * because picking one would be a guess about which debt the user means.
   */
  public Optional<String> soleDebtCurrency(String personName) {
    if (!(matchExact(personName) instanceof PersonMatch.Live live)) {
      return Optional.empty();
    }
    List<String> currencies =
        accountOwnerRepository.findPersonCurrencyBalances(live.person().personId()).stream()
            .map(AccountOwnerRepository.PersonCurrencyBalance::getCurrencyCode)
            .distinct()
            .toList();
    return currencies.size() == 1 ? Optional.of(currencies.get(0)) : Optional.empty();
  }

  /**
   * Classify a typed name against existing persons by exact match (register §3.5, plan stage 8b):
   * the entry dock's {@code for}/{@code by} and Account-field resolution use this to decide whether
   * it can auto-provision straight away, must ask for a revival decision, or must refuse an
   * ambiguous name (data-model §7 allows duplicate live names).
   *
   * @throws IllegalArgumentException if the name is blank
   */
  public PersonMatch matchExact(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Person name cannot be blank");
    }
    List<Person> all = personRepository.findAllByNameExact(name.strip());
    List<Person> live = all.stream().filter(p -> p.deletedAt() == null).toList();
    if (live.size() > 1) {
      return new PersonMatch.Ambiguous(live);
    }
    if (live.size() == 1) {
      return new PersonMatch.Live(live.get(0));
    }
    return all.stream()
        .max(Comparator.comparing(Person::deletedAt))
        .<PersonMatch>map(PersonMatch.DeletedOnly::new)
        .orElseGet(PersonMatch.NotFound::new);
  }
}
