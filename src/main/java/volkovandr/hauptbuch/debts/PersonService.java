package volkovandr.hauptbuch.debts;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
   * Every live person paired with their per-currency debt position (data-model §7, plan stage 8d) —
   * the roster for the People-balances screen, ordered by name like {@link #findAllLive}. Each
   * summary carries only the currencies the person has a <em>non-zero</em> net balance in (a
   * settled person's list is empty) plus their full set of leaf account ids for the register
   * pre-filter link. Base-currency valuation is deliberately not done here: it needs {@code
   * ledger}'s rates, and {@code debts} must not depend on {@code ledger} (that edge already runs
   * the other way), so the {@code operations} assembler layers it on.
   */
  public List<PersonBalanceSummary> balanceSummaries() {
    Map<Long, List<CurrencyBalance>> balancesByPerson =
        accountOwnerRepository.findAllPersonCurrencyBalances().stream()
            .filter(b -> b.getSignedBalance().signum() != 0)
            .collect(
                Collectors.groupingBy(
                    AccountOwnerRepository.PersonCurrencyBalance::getPersonId,
                    Collectors.mapping(
                        b -> new CurrencyBalance(b.getCurrencyCode(), b.getSignedBalance()),
                        Collectors.toList())));
    Map<Long, List<Long>> accountsByPerson =
        accountOwnerRepository.findLiveAccountLinks().stream()
            .collect(
                Collectors.groupingBy(
                    AccountOwner::personId,
                    Collectors.mapping(AccountOwner::accountId, Collectors.toList())));
    return personRepository.findAllLive().stream()
        .map(
            p ->
                new PersonBalanceSummary(
                    p.personId(),
                    p.name(),
                    balancesByPerson.getOrDefault(p.personId(), List.of()),
                    accountsByPerson.getOrDefault(p.personId(), List.of())))
        .toList();
  }

  /**
   * The live person's outstanding position in one currency, for the settle-up launcher (plan stage
   * 8e, data-model §7): the debt leaf to zero and its current signed balance. Empty when the person
   * is not live or holds no leaf in that currency — either way there is nothing to settle. The
   * balance is read fresh (not from a cached figure) so the launcher's direction and default amount
   * reflect the ledger now; a leaf that exists but has no postings reads as a zero balance.
   */
  public Optional<SettleTarget> settleTarget(long personId, String currencyCode) {
    if (personRepository.findById(personId).isEmpty()) {
      return Optional.empty();
    }
    return accountOwnerRepository
        .findLeafAccountId(personId, currencyCode)
        .map(
            accountId ->
                new SettleTarget(accountId, currencyCode, balanceIn(personId, currencyCode)));
  }

  /** The person's summed native balance in one currency, or zero when the leaf has no postings. */
  private BigDecimal balanceIn(long personId, String currencyCode) {
    return accountOwnerRepository.findPersonCurrencyBalances(personId).stream()
        .filter(b -> b.getCurrencyCode().equals(currencyCode))
        .map(AccountOwnerRepository.PersonCurrencyBalance::getSignedBalance)
        .findFirst()
        .orElse(BigDecimal.ZERO);
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
   * The owning person's display name for each of {@code accountIds} that is a per-person debt leaf,
   * keyed by account id (register §2.6, plan stage 8c) — the register renderer's one-shot
   * resolution of the {@code personal.<CUR>} leaves it renders into people's real names. Account
   * ids that are not person leaves are simply absent from the map, which is how the caller tells a
   * person leg from an ordinary account. Resolves a since-soft-deleted person too (a display
   * lookup, not a liveness check).
   */
  public Map<Long, String> personNamesForAccounts(Collection<Long> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
      return Map.of();
    }
    return accountOwnerRepository.findPersonNamesByAccountIds(accountIds).stream()
        .collect(
            Collectors.toMap(
                AccountOwnerRepository.AccountPersonName::accountId,
                AccountOwnerRepository.AccountPersonName::name));
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
