package volkovandr.hauptbuch.debts;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;
import volkovandr.hauptbuch.debts.repository.PersonRepository;

/**
 * The auto-provisioning domain operation for per-person debts (plan stage 8b, data-model §7). When
 * a transaction first attributes a line to a person (the entry dock's {@code for}/{@code by}
 * counterpart, register §3.5), this service ensures the person and the per-currency {@code asset}
 * leaf exist, atomically, and returns the leaf to post to. Called at transaction commit ({@code
 * operations}' {@code DockCommitService}) — never speculatively at typing time.
 *
 * <p>Revival of a soft-deleted person is never silent (data-model §7): the caller must pass an
 * explicit {@code revive} decision, sourced from the dock's Restore/Create-new choice (plan stage
 * 8b) once {@link PersonService#matchExact} finds a name that matches only a soft-deleted row. The
 * decision is irrelevant (ignored) whenever the match is not {@link PersonMatch.DeletedOnly}.
 */
@Service
public class PersonProvisioningService {

  private static final String ACCOUNT_NAME_PREFIX = "personal";

  private final PersonService personService;
  private final PersonRepository personRepository;
  private final AccountOwnerRepository accountOwnerRepository;
  private final AccountService accountService;

  PersonProvisioningService(
      PersonService personService,
      PersonRepository personRepository,
      AccountOwnerRepository accountOwnerRepository,
      AccountService accountService) {
    this.personService = personService;
    this.personRepository = personRepository;
    this.accountOwnerRepository = accountOwnerRepository;
    this.accountService = accountService;
  }

  /**
   * Ensure a person named {@code personName} and their {@code currencyCode} leaf exist, and return
   * the leaf to post to.
   *
   * <ul>
   *   <li>exactly one live person has this name — reuse them ({@code revive} is irrelevant);
   *   <li>no person at all has this name — create one;
   *   <li>only a soft-deleted person has this name — {@code revive == true} restores them, {@code
   *       revive == false} creates a brand-new, distinct person with the same name (data-model §7
   *       allows duplicate names) rather than reviving;
   *   <li>more than one live person shares this name — refused; the caller must have already
   *       stopped the user at resolve time ({@link PersonService#matchExact}), so reaching this is
   *       a caller error, not a user-facing path.
   * </ul>
   *
   * @throws IllegalArgumentException if the name is blank, or the name is ambiguous among live
   *     persons
   */
  @Transactional
  public Account ensureLeaf(String personName, String currencyCode, boolean revive) {
    if (personName == null || personName.isBlank()) {
      throw new IllegalArgumentException("Person name cannot be blank");
    }
    String trimmedName = personName.strip();
    Person person =
        switch (personService.matchExact(trimmedName)) {
          case PersonMatch.Live live -> live.person();
          case PersonMatch.NotFound ignored -> personRepository.insert(trimmedName);
          case PersonMatch.DeletedOnly deletedOnly ->
              revive
                  ? personRepository.revive(deletedOnly.person().personId())
                  : personRepository.insert(trimmedName);
          case PersonMatch.Ambiguous ignored ->
              throw new IllegalArgumentException(
                  "More than one person named '"
                      + trimmedName
                      + "' — rename one via the People page to disambiguate");
        };
    return ensureLeafAccount(person.personId(), currencyCode);
  }

  /**
   * Ensure a per-currency leaf exists for a person and is linked via {@code account_owner},
   * returning it. The leaf is named {@code personal.<CURRENCY>} (cosmetic, data-model §7) and is an
   * {@code asset} type.
   */
  private Account ensureLeafAccount(Long personId, String currencyCode) {
    String leafName = ACCOUNT_NAME_PREFIX + "." + currencyCode;

    for (Long accountId : accountOwnerRepository.findAccountIdsByPersonId(personId)) {
      Optional<Account> account = accountService.findById(accountId);
      if (account.isPresent()
          && account.get().currencyCode().equals(currencyCode)
          && account.get().deletedAt() == null) {
        return account.get();
      }
    }

    Account leaf = accountService.insertLeaf(leafName, "asset", null, currencyCode);
    accountOwnerRepository.insert(leaf.accountId(), personId);
    return leaf;
  }
}
