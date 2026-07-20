package volkovandr.hauptbuch.operations;

import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountEntryLabel;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonResolution;
import volkovandr.hauptbuch.debts.PersonResolutionService;
import volkovandr.hauptbuch.debts.PersonTarget;

/**
 * Resolves the entry dock's Account field text (register §3.3, plan stage 8b.1). The field is a
 * typed datalist offering the open own accounts as {@code Name (CUR)} plus {@code for <person>} /
 * {@code by <person>} for every live person — and accepting a person who does not exist yet, which
 * is the whole reason it replaced the {@code <select>}: "Max paid for a pure expense of mine"
 * (register §2.6 pattern 3) is entered as Account = {@code by Max}, with no separate person field.
 *
 * <p>Lives in {@code operations} because it spans two modules — the account lookup is {@code
 * accounts}, the person one {@code debts} — and {@code operations} may reach both. The person half
 * is delegated to {@code debts}' {@link PersonResolutionService} so the Restore/Create-new revival
 * choice behaves identically here and in the Category field.
 */
@Service
class DockAccountResolutionService {

  private final AccountService accountService;
  private final PersonResolutionService personResolutionService;

  DockAccountResolutionService(
      AccountService accountService, PersonResolutionService personResolutionService) {
    this.accountService = accountService;
    this.personResolutionService = personResolutionService;
  }

  /**
   * Resolve the typed text. A {@code for}/{@code by} sigil names the funding person; anything else
   * is an own account's name, with the {@code (CUR)} suffix the datalist labels carry treated as
   * optional — typing bare {@code Cash} works, and the suffix additionally disambiguates same-named
   * accounts in different currencies.
   *
   * @param text the field's typed value
   * @param personDecision the Restore/Create-new choice, when one has been made
   */
  DockAccountResolution resolve(String text, String personDecision) {
    if (text == null || text.isBlank()) {
      return DockAccountResolution.error("An account or person is required");
    }
    String trimmed = text.strip();

    Optional<PersonTarget.Parsed> person = PersonTarget.parse(trimmed);
    if (person.isPresent()) {
      return resolvePerson(person.get(), personDecision);
    }
    return resolveAccount(trimmed);
  }

  private DockAccountResolution resolvePerson(PersonTarget.Parsed parsed, String personDecision) {
    return switch (personResolutionService.resolve(parsed, personDecision)) {
      case PersonResolution.Refused refused -> DockAccountResolution.error(refused.message());
      case PersonResolution.Pending pending ->
          DockAccountResolution.pendingRevival(pending.personName());
      case PersonResolution.Resolved resolved ->
          DockAccountResolution.person(
              resolved.personName(),
              resolved.direction(),
              resolved.revive(),
              resolved.statusText());
    };
  }

  /**
   * Resolve {@code Name} or {@code Name (CUR)} to an open own account. A currency suffix that does
   * not match the named account's own currency is refused rather than ignored — silently dropping
   * it would fund the transaction from an account the user did not name.
   */
  private DockAccountResolution resolveAccount(String text) {
    AccountEntryLabel.Parsed parsed = AccountEntryLabel.parse(text);
    Optional<Account> match = accountService.findOwnAccountByName(parsed.name());
    if (match.isEmpty()) {
      return DockAccountResolution.error(
          "No open account named '"
              + parsed.name()
              + "' — pick one, or type 'for "
              + parsed.name()
              + "' / 'by "
              + parsed.name()
              + "' to make it a person");
    }
    Account account = match.get();
    if (parsed.currencyCode() != null
        && !parsed.currencyCode().equalsIgnoreCase(account.currencyCode())) {
      return DockAccountResolution.error(
          "'"
              + parsed.name()
              + "' is in "
              + account.currencyCode()
              + ", not "
              + parsed.currencyCode());
    }
    return DockAccountResolution.account(account.accountId(), AccountEntryLabel.format(account));
  }
}
