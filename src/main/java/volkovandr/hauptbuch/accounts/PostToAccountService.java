package volkovandr.hauptbuch.accounts;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * The <em>post-to set</em> (CONTEXT.md) — the own accounts a transaction may be booked to — as
 * every own-account picker offers it: the register dock and split panel, the transfer targets, the
 * receipt screen's paying account, and Settle-up (issue transaction-register-ui/25). One source, so
 * no picker can drift into offering an account another refuses.
 *
 * <p>An account is in the set when it is a live, open, real (not per-person) asset or liability
 * <em>posting leaf</em>: an account with a child is a <em>group</em>, reached only through its
 * children (leaves-only, data-model §5). Each is named by its full {@code Parent - Leaf} path, the
 * idiom the Category picker uses, so two same-named leaves under different parents stay apart.
 */
@Service
public class PostToAccountService {

  /** The own account types (data-model §3.2). */
  private static final List<String> OWN_TYPES = AccountService.MANAGEABLE_TYPES;

  /** The path separator, shared with the Category picker (register §3.5). */
  static final String PATH_SEPARATOR = " - ";

  private final AccountRepository accountRepository;

  PostToAccountService(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  /** The post-to set, sorted by path ignoring case. */
  public List<PostToAccount> postToAccounts() {
    return ownTree().postTo();
  }

  /**
   * Read a picked or typed label back to an account of the post-to set. The whole text is tried
   * first — as a path, then as a bare leaf name — because a name may itself end in a currency
   * ({@code Card (EUR)}); only when nothing matches is a trailing {@code (CUR)} split off, and it
   * then narrows the match to accounts in that currency. A path match outranks a bare-name match.
   */
  public PostToResolution resolve(String text) {
    return ownTree().resolve(text.strip());
  }

  /**
   * The refusal for an account id that is a group, or empty when it is not one — for the pickers
   * that post an id rather than a label (the receipt paying account, Settle-up). Their selects
   * offer posting leaves only, but a stale form can still carry a group's id; refused with the same
   * message the label resolver gives, never by the ledger's leaves-only rule at commit.
   */
  public Optional<PostToResolution.Group> groupOf(long accountId) {
    return ownTree().groupOf(accountId);
  }

  private OwnTree ownTree() {
    List<Account> accounts =
        accountRepository.findLiveByTypesWithDepth(OWN_TYPES).stream()
            .map(AccountNode::account)
            .toList();
    return new OwnTree(accounts);
  }

  /**
   * An account a label may name: a post-to leaf, or a group the resolver refuses by name.
   *
   * @param account the account
   * @param path its full path
   * @param group whether it has children
   */
  private record Candidate(Account account, String path, boolean group) {

    /** A leaf's picker label; a group carries no currency, so its path alone. */
    String label() {
      return group ? path : AccountEntryLabel.format(path, account.currencyCode());
    }
  }

  /** The live own accounts, indexed for path composition and the leaf/group split. */
  private static final class OwnTree {

    private final List<Account> accounts;
    private final Map<Long, Account> byId = new HashMap<>();
    private final Set<Long> parentIds = new HashSet<>();

    OwnTree(List<Account> accounts) {
      this.accounts = accounts;
      for (Account account : accounts) {
        byId.put(account.accountId(), account);
        if (account.parentId() != null) {
          parentIds.add(account.parentId());
        }
      }
    }

    List<PostToAccount> postTo() {
      return candidates().stream()
          .filter(c -> !c.group())
          .map(c -> new PostToAccount(c.account(), c.path()))
          .sorted(Comparator.comparing(PostToAccount::path, String.CASE_INSENSITIVE_ORDER))
          .toList();
    }

    Optional<PostToResolution.Group> groupOf(long accountId) {
      Account account = byId.get(accountId);
      if (account == null || !parentIds.contains(accountId)) {
        return Optional.empty();
      }
      return Optional.of(
          new PostToResolution.Group(AccountService.composePath(account, byId, PATH_SEPARATOR)));
    }

    PostToResolution resolve(String text) {
      List<Candidate> whole = match(text);
      if (!whole.isEmpty()) {
        return outcome(whole);
      }
      AccountEntryLabel.Parsed parsed = AccountEntryLabel.parse(text);
      if (parsed.currencyCode() == null) {
        return new PostToResolution.NotFound(text);
      }
      List<Candidate> byName = match(parsed.name());
      if (byName.isEmpty()) {
        return new PostToResolution.NotFound(parsed.name());
      }
      String currency = parsed.currencyCode().toUpperCase(Locale.ROOT);
      List<Candidate> inCurrency =
          byName.stream()
              .filter(c -> c.group() || c.account().currencyCode().equalsIgnoreCase(currency))
              .toList();
      if (inCurrency.isEmpty()) {
        return byName.size() == 1
            ? new PostToResolution.WrongCurrency(byName.get(0).label(), currency)
            : ambiguous(byName);
      }
      return outcome(inCurrency);
    }

    /** The candidates whose path is {@code text}, else those whose own name is. */
    private List<Candidate> match(String text) {
      List<Candidate> candidates = candidates();
      List<Candidate> byPath =
          candidates.stream().filter(c -> c.path().equalsIgnoreCase(text)).toList();
      if (!byPath.isEmpty()) {
        return byPath;
      }
      return candidates.stream().filter(c -> c.account().name().equalsIgnoreCase(text)).toList();
    }

    private static PostToResolution outcome(List<Candidate> matches) {
      if (matches.size() > 1) {
        return ambiguous(matches);
      }
      Candidate match = matches.get(0);
      return match.group()
          ? new PostToResolution.Group(match.path())
          : new PostToResolution.Resolved(new PostToAccount(match.account(), match.path()));
    }

    private static PostToResolution ambiguous(List<Candidate> matches) {
      return new PostToResolution.Ambiguous(
          matches.stream().map(Candidate::label).sorted(String.CASE_INSENSITIVE_ORDER).toList());
    }

    /** Every bookable own account a label may name — the post-to leaves and the groups. */
    private List<Candidate> candidates() {
      return accounts.stream()
          .filter(OwnTree::bookable)
          .map(
              a ->
                  new Candidate(
                      a,
                      AccountService.composePath(a, byId, PATH_SEPARATOR),
                      parentIds.contains(a.accountId())))
          .toList();
    }

    /** Open and real — a closed account is viewable but not bookable, a person leaf is a sigil. */
    private static boolean bookable(Account account) {
      return account.closedAt() == null && !account.personLeaf() && !account.currencyLeaf();
    }
  }
}
