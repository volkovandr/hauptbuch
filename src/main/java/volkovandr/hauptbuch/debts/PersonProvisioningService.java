package volkovandr.hauptbuch.debts;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;
import volkovandr.hauptbuch.debts.repository.PersonRepository;

/**
 * The auto-provisioning domain operation for per-person debts (plan stage 8a, data-model §7). When
 * a transaction first references a person in a given currency, this service ensures the person, the
 * per-currency `asset` leaf, and the owner link exist, atomically. Revival of a soft-deleted person
 * is confirmed (never silent).
 *
 * <p>Entry rides the transfer path; a person leg is a posting to that person's per-currency
 * account. This service is called at transaction commit (dock commit) to prepare the accounts.
 */
@Service
public class PersonProvisioningService {

  private static final String ACCOUNT_NAME_PREFIX = "personal";

  private final PersonRepository personRepository;
  private final AccountOwnerRepository accountOwnerRepository;
  private final AccountService accountService;

  PersonProvisioningService(
      PersonRepository personRepository,
      AccountOwnerRepository accountOwnerRepository,
      AccountService accountService) {
    this.personRepository = personRepository;
    this.accountOwnerRepository = accountOwnerRepository;
    this.accountService = accountService;
  }

  /**
   * Ensure a person with a given name and a per-currency leaf account exist and are linked. Called
   * at transaction commit.
   *
   * <p>If the person exists (live), return it. If only a soft-deleted version exists with the same
   * name, this method returns it without reviving — the caller must confirm revival (it is never
   * silent). If no person with this name exists, create one.
   *
   * <p>For the person returned (whether existing or newly created), ensure a per-currency `asset`
   * leaf with name `personal.<CURRENCY>` exists and is linked via `account_owner`. The leaf is
   * created if missing.
   *
   * @param personName the person's name
   * @param currencyCode the currency for the per-currency leaf
   * @return the person (may be soft-deleted if that is the only match)
   */
  @Transactional
  public Person ensurePersonWithLeaves(String personName, String currencyCode) {
    if (personName == null || personName.isBlank()) {
      throw new IllegalArgumentException("Person name cannot be blank");
    }

    // Try to find a live person; if none, try to find a soft-deleted one.
    Optional<Person> livePerson = personRepository.findByNameExact(personName);
    Person person =
        livePerson.orElseGet(
            () ->
                personRepository
                    .findByNameExactIncludingDeleted(personName)
                    .orElseGet(() -> personRepository.insert(personName)));

    // Ensure the per-currency leaf exists for this person.
    ensurePersonLeaf(person.personId(), currencyCode);

    return person;
  }

  /**
   * Ensure a per-currency leaf exists for a person and is linked via account_owner. The leaf is
   * named `personal.<CURRENCY>` (cosmetic, per data-model §7) and is an `asset` type.
   */
  private void ensurePersonLeaf(Long personId, String currencyCode) {
    String leafName = ACCOUNT_NAME_PREFIX + "." + currencyCode;

    // Check if this person already has an account in this currency.
    java.util.List<Long> existingAccounts =
        accountOwnerRepository.findAccountIdsByPersonId(personId);
    for (Long accountId : existingAccounts) {
      Optional<volkovandr.hauptbuch.accounts.Account> account = accountService.findById(accountId);
      if (account.isPresent()
          && account.get().currencyCode().equals(currencyCode)
          && account.get().deletedAt() == null) {
        // Leaf already exists and is live.
        return;
      }
    }

    // Create the leaf: standalone asset with no parent, type "asset", in the given currency.
    volkovandr.hauptbuch.accounts.Account leaf =
        accountService.insertLeaf(leafName, "asset", null, currencyCode);

    // Link it to the person.
    accountOwnerRepository.insert(leaf.accountId(), personId);
  }
}
