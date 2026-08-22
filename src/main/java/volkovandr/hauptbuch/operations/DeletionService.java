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
   * <p>A subtree no posting has ever hit — live or voided — has nothing to reassign, so it deletes
   * with no target at all ({@code targetLeafId} {@code null}). A subtree that does carry postings
   * still requires one: dropping the postings silently is never the answer.
   *
   * @param subtreeRootId the category to delete; its whole subtree goes with it
   * @param targetLeafId the surviving leaf that receives every reassigned posting, or {@code null}
   *     when the subtree carries no postings
   * @throws IllegalArgumentException if the subtree root is not a live category, the target is
   *     absent while the subtree carries postings, or the target does not exist, is not a leaf, or
   *     is the subtree root or one of its descendants
   */
  @Transactional
  public void deleteCategory(long subtreeRootId, Long targetLeafId) {
    // The walk is scoped to live rows and always includes the root, so an empty subtree means the
    // root is already gone (a double-submit, or a back-button re-post of the delete form). Say so
    // rather than reassigning and stamping nothing and reporting success.
    List<Long> subtree = accountService.findSubtreeAccountIds(subtreeRootId);
    if (subtree.isEmpty()) {
      throw new IllegalArgumentException(
          "No live category with id " + subtreeRootId + " — it may already have been deleted");
    }

    if (targetLeafId == null) {
      if (accountService.hasAnyPostings(subtree)) {
        throw new IllegalArgumentException(
            "This category carries postings — a category to move them to is required");
      }
      accountService.softDelete(subtree);
      return;
    }

    requireUsableTarget(targetLeafId, subtree);
    postingReassignmentRepository.reassignPostings(subtree, targetLeafId);
    accountService.softDelete(subtree);
  }

  /**
   * The target must be a live account outside the subtree being deleted, and a leaf once that
   * deletion has happened — postings land only on leaves (data-model §5).
   */
  private void requireUsableTarget(long targetLeafId, List<Long> subtree) {
    Account target =
        accountService
            .findById(targetLeafId)
            .orElseThrow(() -> new IllegalArgumentException("No account with id " + targetLeafId));

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
  }
}
