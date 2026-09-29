package volkovandr.hauptbuch.recurring;

import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Whether a template can still book (data-model §14.3, recurring sub-plan slice f): every account,
 * category and person it names must still be usable. The dock checks none of this itself: it posts
 * to a closed account (an old transaction may be edited there), and it routes a category that has
 * since gained subcategories into whichever child is in the right currency. A hand-typed entry
 * cannot name those, since the pickers leave them out, but a template stored before the change can.
 * So the run checks first, and the failure names the cause for the operator to fix.
 */
@Component
class RecurringBookability {

  private final RecurringTemplateRepository repository;
  private final AccountService accountService;
  private final PersonService personService;

  RecurringBookability(
      RecurringTemplateRepository repository,
      AccountService accountService,
      PersonService personService) {
    this.repository = repository;
    this.accountService = accountService;
    this.personService = personService;
  }

  /**
   * Refuse a template that names a closed or deleted account, a deleted category or one with
   * subcategories, or a deleted person.
   *
   * @throws IllegalStateException naming the first unusable reference
   */
  void requireBookable(RecurringTemplate template) {
    if (template.personId() != null) {
      requireLivePerson(template.personId());
    } else {
      requireOpenAccount(template.accountId());
    }
    for (RecurringTemplateLine line : repository.findLines(template.recurringTemplateId())) {
      if (line.personId() != null) {
        requireLivePerson(line.personId());
      } else if (line.transferDirection() != null) {
        requireOpenAccount(line.accountId());
      } else {
        requireLeafCategory(line.accountId());
      }
    }
  }

  private void requireOpenAccount(long accountId) {
    Account account = live(accountId, "Account");
    if (account.closedAt() != null) {
      throw new IllegalStateException("Account '" + account.name() + "' is closed");
    }
  }

  /** Currency leaves under the category are its own; only a real subcategory makes it a parent. */
  private void requireLeafCategory(long accountId) {
    Account category = live(accountId, "Category");
    boolean hasSubcategories =
        accountService.findChildrenOf(accountId).stream().anyMatch(c -> !c.currencyLeaf());
    if (hasSubcategories) {
      throw new IllegalStateException(
          "Category '" + category.name() + "' has subcategories: pick one of them in the template");
    }
  }

  private Account live(long accountId, String what) {
    Account account =
        accountService
            .findById(accountId)
            .orElseThrow(() -> new IllegalStateException(what + " #" + accountId + " is missing"));
    if (account.deletedAt() != null) {
      throw new IllegalStateException(what + " '" + account.name() + "' was deleted");
    }
    return account;
  }

  private void requireLivePerson(long personId) {
    if (personService.findById(personId).isEmpty()) {
      throw new IllegalStateException("A person the template names was deleted");
    }
  }
}
