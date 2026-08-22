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
 * @param needsTarget whether any posting — live or voided — has ever hit the subtree being deleted
 * @param targets the live leaves that may receive those postings (empty when none qualifies)
 */
public record CategoryDeletePanel(boolean needsTarget, List<AccountNode> targets) {

  /** Defensive copy of the target list (the house pattern for record lists). */
  public CategoryDeletePanel {
    targets = List.copyOf(targets);
  }
}
