package volkovandr.hauptbuch.operations;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.operations.repository.PostingReassignmentRepository;

/**
 * The category-deletion domain operation (plan stage 6c, data-model §5). The mirror image of {@link
 * SubdivisionService}: where subdivision turns one leaf into a parent by spawning children,
 * deletion removes a whole subtree at once, its postings converging onto one surviving target leaf
 * (many sources → one target, not one source → new child).
 *
 * <p>Deleting a parent deletes its <em>entire</em> subtree — every descendant row, not just the
 * named parent — after moving all of their postings onto the target. The target must be a live leaf
 * that is not the category being deleted nor any of its descendants: a target inside the subtree
 * would itself be deleted, so its postings would vanish (data-model §5).
 *
 * <p>Deletion is soft (stamps {@code deleted_at}), consistent with the rest of the model, but
 * unlike account close it is not surfaced as reversible — the categories screen offers no reopen.
 */
@Service
public class DeletionService {

  private final AccountService accountService;
  private final PostingReassignmentRepository postingReassignmentRepository;

  DeletionService(
      AccountService accountService, PostingReassignmentRepository postingReassignmentRepository) {
    this.accountService = accountService;
    this.postingReassignmentRepository = postingReassignmentRepository;
  }

  /**
   * Delete a category subtree, reassigning every posting under it onto {@code targetLeafId}.
   *
   * @param subtreeRootId the category to delete; its whole subtree goes with it
   * @param targetLeafId the surviving leaf that receives every reassigned posting
   * @throws IllegalArgumentException if the target does not exist, is not a leaf, or is the subtree
   *     root or one of its descendants
   */
  @Transactional
  public void deleteCategory(long subtreeRootId, long targetLeafId) {
    Account target =
        accountService
            .findById(targetLeafId)
            .orElseThrow(() -> new IllegalArgumentException("No account with id " + targetLeafId));

    List<Long> subtree = accountService.findSubtreeAccountIds(subtreeRootId);
    if (subtree.contains(targetLeafId)) {
      throw new IllegalArgumentException(
          "Target '"
              + target.name()
              + "' is within the subtree being deleted — it must survive the deletion");
    }

    // Leaves-only is judged against the post-deletion state: children inside the subtree are about
    // to be gone, so the target is a valid leaf as long as none of its children survive. E.g.
    // deleting M&Ms leaves its parent Sweets childless — Sweets may then receive the postings.
    boolean hasSurvivingChild =
        accountService.findChildrenOf(targetLeafId).stream()
            .anyMatch(child -> !subtree.contains(child.accountId()));
    if (hasSurvivingChild) {
      throw new IllegalArgumentException(
          "Target '" + target.name() + "' is not a leaf — postings can only land on a leaf");
    }

    postingReassignmentRepository.reassignPostings(subtree, targetLeafId);
    accountService.softDelete(subtree);
  }
}
