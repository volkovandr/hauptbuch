package volkovandr.hauptbuch.operations;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.accounts.PostToAccountService;
import volkovandr.hauptbuch.accounts.PostToResolution;
import volkovandr.hauptbuch.debts.PersonTarget;
import volkovandr.hauptbuch.ledger.TransferTarget;

/**
 * The commit-time guard against a split line whose visible text and hidden resolved state have
 * drifted apart (issue transaction-register-ui/30). A line's text is what the operator sees and
 * believes they are booking to; its hidden id / transfer direction / person is what the commit
 * actually uses. The resolve round-trip keeps the two equal, but it fires on {@code change} and can
 * lose a race with Save, so a stale id can reach the commit under text that names something else.
 *
 * <p>This checks the posted form <em>by kind</em>, mirroring how {@code /categories/resolve}
 * classifies text — a {@code To →}/{@code From ←} target, a {@code for}/{@code by} person, else a
 * category — and refuses a line whose hidden state does not belong to its text. It re-reads the
 * state through {@code accounts} lookups; it cannot call {@code categories}' resolver (the module
 * cycle in the plan's stage 7 boundary note), and it deliberately refuses only a <em>positive</em>
 * mismatch: a line the resolver never finished (text but no id) is left to the binder's "needs a
 * category" message.
 */
@Service
public class SplitLineConsistency {

  private static final List<String> CATEGORY_TYPES = List.of("income", "expense");
  private static final String PATH_SEPARATOR = " - ";

  private final AccountService accountService;
  private final PostToAccountService postToAccountService;

  SplitLineConsistency(AccountService accountService, PostToAccountService postToAccountService) {
    this.accountService = accountService;
    this.postToAccountService = postToAccountService;
  }

  /**
   * Check every non-blank line of the posted form.
   *
   * @throws IllegalArgumentException naming the first line (as displayed, 1-based) whose text and
   *     resolved state disagree, asking for it to be picked again
   */
  public void verify(SplitForm form) {
    // The panel always posts each line's text; with none posted there is nothing to compare.
    if (form.categoryText() == null || form.categoryText().isEmpty()) {
      return;
    }
    int count =
        Math.max(
            size(form.categoryText()),
            Math.max(size(form.lineCategoryId()), size(form.linePersonName())));
    for (int i = 0; i < count; i++) {
      String text = SplitFormBinder.at(form.categoryText(), i).strip();
      String id = SplitFormBinder.at(form.lineCategoryId(), i).strip();
      String direction = SplitFormBinder.at(form.lineTransferDirection(), i).strip();
      String personName = SplitFormBinder.at(form.linePersonName(), i).strip();
      String personDirection = SplitFormBinder.at(form.linePersonDirection(), i).strip();
      if (text.isEmpty() && id.isEmpty() && personName.isEmpty()) {
        continue; // an empty line the user never filled in
      }
      if (!agrees(text, id, direction, personName, personDirection)) {
        throw refusal(i + 1, text);
      }
    }
  }

  /** The refusal for line {@code number} (as displayed), naming what the operator typed. */
  private static IllegalArgumentException refusal(int number, String text) {
    String what =
        text.isEmpty()
            ? "the category is blank"
            : "'" + text + "' no longer matches what was picked";
    return new IllegalArgumentException(
        "Line " + number + ": " + what + " — pick the category again from the list");
  }

  private boolean agrees(
      String text, String id, String direction, String personName, String personDirection) {
    Optional<TransferTarget.Parsed> transfer = TransferTarget.parse(text);
    if (transfer.isPresent()) {
      return agreesTransfer(transfer.get(), id, direction, personName);
    }
    Optional<PersonTarget.Parsed> person = PersonTarget.parse(text);
    if (person.isPresent()) {
      return agreesPerson(person.get(), id, direction, personName, personDirection);
    }
    return agreesCategory(text, id, direction, personName);
  }

  /** A transfer: the same direction, and the label still names the account whose id is carried. */
  private boolean agreesTransfer(
      TransferTarget.Parsed transfer, String id, String direction, String personName) {
    if (id.isEmpty()) {
      return true; // unresolved: the binder reports it
    }
    if (!personName.isEmpty() || !transfer.direction().name().equalsIgnoreCase(direction)) {
      return false;
    }
    // Only a *positive* different resolution is a mismatch: a label the post-to set no longer
    // resolves (a since-closed account, say) is not evidence of drift.
    Optional<Long> resolved = resolvedAccountId(transfer.accountName());
    return resolved.isEmpty() || resolved.get().toString().equals(id);
  }

  private Optional<Long> resolvedAccountId(String label) {
    if (postToAccountService.resolve(label) instanceof PostToResolution.Resolved found) {
      return Optional.of(found.account().account().accountId());
    }
    return Optional.empty();
  }

  /** A person: the carried name and direction are the ones the text names, and no id is carried. */
  private static boolean agreesPerson(
      PersonTarget.Parsed person,
      String id,
      String direction,
      String personName,
      String personDirection) {
    if (personName.isEmpty()) {
      // Unresolved (or a pending revive decision): the binder reports it — but only if nothing
      // stale is carried, since the resolver blanks the id and direction for a person.
      return id.isEmpty() && direction.isEmpty();
    }
    return id.isEmpty()
        && direction.isEmpty()
        && person.personName().equalsIgnoreCase(personName)
        && person.direction().name().equalsIgnoreCase(personDirection);
  }

  /**
   * A category: no transfer direction or person is carried, and the text is the carried account's
   * bare name or its full path — the two forms the resolver accepts.
   */
  private boolean agreesCategory(String text, String id, String direction, String personName) {
    if (id.isEmpty()) {
      return personName.isEmpty(); // unresolved text is the binder's to report; a person is not
    }
    if (!direction.isEmpty() || !personName.isEmpty() || text.isEmpty()) {
      return false;
    }
    Optional<Account> account = accountService.findById(Long.parseLong(id));
    if (account.isEmpty() || !CATEGORY_TYPES.contains(account.get().type())) {
      return false;
    }
    return text.equalsIgnoreCase(account.get().name())
        || pathOf(account.get()).equalsIgnoreCase(text);
  }

  private String pathOf(Account account) {
    return accountService.findLivePaths(CATEGORY_TYPES, PATH_SEPARATOR).stream()
        .filter(p -> p.accountId() == account.accountId())
        .map(AccountPath::path)
        .findFirst()
        .orElse(account.name());
  }

  private static int size(List<String> list) {
    return list == null ? 0 : list.size();
  }
}
