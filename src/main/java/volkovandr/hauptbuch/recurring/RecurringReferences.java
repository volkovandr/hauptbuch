package volkovandr.hauptbuch.recurring;

import java.util.List;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.operations.ReferenceHolder;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Recurring templates as a {@link ReferenceHolder} (data-model §14.3, recurring sub-plan slice f):
 * a person merge and a subdivision rewrite the templates' references as they rewrite the postings,
 * and deleting a category a live template uses is refused, naming the template.
 */
@Component
class RecurringReferences implements ReferenceHolder {

  private final RecurringTemplateRepository repository;

  RecurringReferences(RecurringTemplateRepository repository) {
    this.repository = repository;
  }

  @Override
  public void reassignPerson(long fromPersonId, long toPersonId) {
    repository.reassignPerson(fromPersonId, toPersonId);
  }

  @Override
  public void reassignAccount(long fromAccountId, long toAccountId) {
    repository.reassignAccount(fromAccountId, toAccountId);
  }

  @Override
  public List<String> usersOf(List<Long> accountIds) {
    return repository.findLiveNamesUsingAccounts(accountIds).stream()
        .map(name -> "recurring template '" + name + "'")
        .toList();
  }
}
