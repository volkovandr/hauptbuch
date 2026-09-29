package volkovandr.hauptbuch.categories;

import java.util.List;
import volkovandr.hauptbuch.accounts.AccountNode;

/**
 * The delete section's state for one category on its edit page (plan stage 6c, issue
 * category-management/06): whether the deletion needs somewhere to move postings to at all, and —
 * if so — the leaves it may move them to.
 *
 * <p>The two travel together because they answer one question between them. A subtree no posting
 * has ever hit deletes outright, so the panel shows the delete button with no picker; only a
 * subtree that carries postings needs one, and only then is an empty {@code targets} list a reason
 * to refuse ("create one first").
 *
 * <p>A subtree a live recurring template still uses cannot be deleted at all (data-model §14.3):
 * {@code usedBy} names those templates, and the panel shows them instead of the delete button.
 *
 * @param needsTarget whether any posting — live or voided — has ever hit the subtree being deleted
 * @param targets the live leaves that may receive those postings (empty when none qualifies)
 * @param usedBy what still uses the subtree, e.g. {@code recurring template 'Streaming'}
 */
public record CategoryDeletePanel(
    boolean needsTarget, List<AccountNode> targets, List<String> usedBy) {

  /** Defensive copies of the lists (the house pattern for record lists). */
  public CategoryDeletePanel {
    targets = List.copyOf(targets);
    usedBy = List.copyOf(usedBy);
  }

  /** The postings need a target and none qualifies ("create one first"). */
  public boolean lacksTarget() {
    return needsTarget && targets.isEmpty();
  }

  /** Whether the panel offers the delete button: nothing uses the subtree, and a target exists. */
  public boolean deletable() {
    return usedBy.isEmpty() && !lacksTarget();
  }
}
