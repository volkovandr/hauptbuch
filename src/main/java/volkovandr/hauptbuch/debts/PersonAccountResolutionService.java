package volkovandr.hauptbuch.debts;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.repository.AccountOwnerRepository;

/**
 * Resolves the entry dock's person sub-field (register §3.3, plan stage 8b) — typing a name there
 * makes an <em>already-established</em> person the funding leg, the "Max paid for a pure expense of
 * mine" case (register §2.6 pattern 3). Unlike the Category field's {@code for}/{@code by} (data-
 * model §7), this never auto-provisions: a person with no per-currency leaf yet is refused, pointed
 * at the Category field instead — the funding leg's currency has no other source to default from
 * here (no funding account to inherit it, no currency-override selector), so a first reference must
 * go through {@code for}/{@code by}'s currency handling.
 *
 * <p>A name matching a person with exactly one currency leaf resolves straight to it. A person with
 * more than one (a multi-currency debt) requires a {@code Name (CUR)} suffix to disambiguate — the
 * same display convention the real-account picker already uses.
 */
@Service
public class PersonAccountResolutionService {

  private final AccountService accountService;
  private final PersonService personService;
  private final AccountOwnerRepository accountOwnerRepository;

  PersonAccountResolutionService(
      AccountService accountService,
      PersonService personService,
      AccountOwnerRepository accountOwnerRepository) {
    this.accountService = accountService;
    this.personService = personService;
    this.accountOwnerRepository = accountOwnerRepository;
  }

  /**
   * Resolve typed text to an existing account: a real own account by name, or a person's
   * per-currency debt leaf by name (optionally suffixed {@code (CUR)} to disambiguate).
   *
   * @throws IllegalArgumentException with a user-facing message when nothing resolves
   */
  public Account resolve(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("An account or person is required");
    }
    Parsed parsed = parse(text.strip());

    Optional<Account> realAccount = accountService.findOwnAccountByName(parsed.name());
    if (realAccount.isPresent()
        && (parsed.currencyCode() == null
            || parsed.currencyCode().equalsIgnoreCase(realAccount.get().currencyCode()))) {
      return realAccount.get();
    }

    return resolvePersonLeaf(parsed);
  }

  private Account resolvePersonLeaf(Parsed parsed) {
    if (!(personService.matchExact(parsed.name()) instanceof PersonMatch.Live live)) {
      throw new IllegalArgumentException(
          "No account or person named '"
              + parsed.name()
              + "' — a new person is established with 'for "
              + parsed.name()
              + "' / 'by "
              + parsed.name()
              + "' in Category");
    }
    List<Account> leaves = leavesOf(live.person().personId());
    if (leaves.isEmpty()) {
      throw new IllegalArgumentException(
          "'"
              + parsed.name()
              + "' has no debt account yet — establish it with 'for "
              + parsed.name()
              + "' / 'by "
              + parsed.name()
              + "' in Category first");
    }
    if (parsed.currencyCode() != null) {
      return leaves.stream()
          .filter(a -> a.currencyCode().equalsIgnoreCase(parsed.currencyCode()))
          .findFirst()
          .orElseThrow(
              () ->
                  new IllegalArgumentException(
                      "'" + parsed.name() + "' has no debt in " + parsed.currencyCode()));
    }
    if (leaves.size() > 1) {
      String currencies =
          leaves.stream()
              .map(Account::currencyCode)
              .sorted()
              .distinct()
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      throw new IllegalArgumentException(
          "'"
              + parsed.name()
              + "' has debts in more than one currency ("
              + currencies
              + ") — type '"
              + parsed.name()
              + " (CUR)' to pick one");
    }
    return leaves.get(0);
  }

  private List<Account> leavesOf(Long personId) {
    return accountOwnerRepository.findAccountIdsByPersonId(personId).stream()
        .map(accountService::findById)
        .flatMap(Optional::stream)
        .filter(a -> a.deletedAt() == null)
        .toList();
  }

  /** Split {@code "Name (CUR)"} into its name and optional currency suffix. */
  private static Parsed parse(String text) {
    int open = text.lastIndexOf('(');
    if (open > 0 && text.endsWith(")")) {
      String currency = text.substring(open + 1, text.length() - 1).strip();
      String name = text.substring(0, open).strip();
      if (!currency.isBlank() && !name.isBlank()) {
        return new Parsed(name, currency);
      }
    }
    return new Parsed(text, null);
  }

  private record Parsed(String name, String currencyCode) {}
}
