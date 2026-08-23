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
 * deletion removes a whole subtree at once, its postings converging onto one surviving target
 * category (many sources → one target, not one source → new child).
 *
 * <p>Deleting a parent deletes its <em>entire</em> subtree — every descendant row, not just the
 * named parent — after moving all of their postings onto the target. The target must be a live
 * category of the same type that is not the category being deleted nor any of its descendants: a
 * target inside the subtree would itself be deleted, so its postings would vanish (data-model §5).
 *
 * <p>Reassignment is <strong>currency-preserving</strong> (issue category-management/05). A posting
 * carries no currency of its own — it is denominated in its account's — so moving a CHF posting
 * onto a EUR category would silently reinterpret the amount. The target is therefore chosen
 * <em>semantically</em>, exactly as the entry dock's Category field is, and each currency's
 * postings are routed through {@link CurrencyLeafService#resolveCurrencyLeaf} to the target's leaf
 * for that same currency, provisioned on first use (data-model §6.5).
 *
 * <p>Deletion is soft (stamps {@code deleted_at}), consistent with the rest of the model, but
 * unlike account close it is not surfaced as reversible — the categories screen offers no reopen.
 */
@Service
public class DeletionService {

  /**
   * Every target refusal names the target, so the prefix is shared (PMD AvoidDuplicateLiterals).
   */
  private static final String TARGET = "Target '";

  /** The account types that are categories (data-model §6.5) — the only ones this deletes. */
  private static final List<String> CATEGORY_TYPES = List.of("income", "expense");

  private final AccountService accountService;
  private final CurrencyLeafService currencyLeafService;
  private final PostingReassignmentRepository postingReassignmentRepository;

  DeletionService(
      AccountService accountService,
      CurrencyLeafService currencyLeafService,
      PostingReassignmentRepository postingReassignmentRepository) {
    this.accountService = accountService;
    this.currencyLeafService = currencyLeafService;
    this.postingReassignmentRepository = postingReassignmentRepository;
  }

  /**
   * Delete a category subtree, reassigning every posting under it onto the category {@code
   * targetLeafId} names — each posting landing on that category's leaf for its own currency.
   *
   * <p>A subtree no posting has ever hit — live or voided — has nothing to reassign, so it deletes
   * with no target at all ({@code targetLeafId} {@code null}). A subtree that does carry postings
   * still requires one: dropping the postings silently is never the answer.
   *
   * @param subtreeRootId the category to delete; its whole subtree goes with it
   * @param targetLeafId the surviving category that receives the reassigned postings, or {@code
   *     null} when the subtree carries no postings
   * @throws IllegalArgumentException if the subtree root is not a live category, the target is
   *     absent while the subtree carries postings, or the target does not exist, is not a live
   *     category of the same type, still has real subcategories, or is the subtree root or one of
   *     its descendants
   */
  @Transactional
  public void deleteCategory(long subtreeRootId, Long targetLeafId) {
    // The walk is scoped to live rows and always includes the root, so an empty subtree means the
    // root is already gone (a double-submit, or a back-button re-post of the delete form). Say so
    // rather than reassigning and stamping nothing and reporting success.
    List<Account> subtree = accountService.findSubtreeAccounts(subtreeRootId);
    Account root =
        subtree.stream()
            .filter(a -> a.accountId() == subtreeRootId)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No live category with id "
                            + subtreeRootId
                            + " — it may already have been deleted"));
    if (!CATEGORY_TYPES.contains(root.type())) {
      throw new IllegalArgumentException(
          "'" + root.name() + "' is a " + root.type() + " account, not a category");
    }
    List<Long> subtreeIds = subtree.stream().map(Account::accountId).toList();

    if (targetLeafId == null) {
      if (accountService.hasAnyPostings(subtreeIds)) {
        throw new IllegalArgumentException(
            "This category carries postings — a category to move them to is required");
      }
      accountService.softDelete(subtreeIds);
      return;
    }

    Account target = requireUsableTarget(targetLeafId, root, subtreeIds);
    // Stamp the subtree first, then route: the target may be the deleted root's own parent (issue
    // 05's Sweets case), and the routing reads *live* children. With the subtree still live it
    // would pick the doomed child as the target's leaf for that currency and the postings would go
    // down with it. Both halves are in one transaction, so nothing is ever observed half-moved.
    accountService.softDelete(subtreeIds);
    reassignPerCurrency(subtree, target);
  }

  /**
   * Move the subtree's postings onto the target, one currency at a time — each currency's sources
   * to the target's leaf for that currency (data-model §6.5).
   *
   * <p>Resolve-then-reassign per currency, in sequence, is correctness rather than taste: resolving
   * a second currency can promote the target from a leaf to a parent, which would invalidate a leaf
   * id resolved earlier. Done in this order, that later subdivision carries the already-reassigned
   * postings with it. Currencies the subtree holds no postings in are skipped entirely — a leaf
   * appears only when it is spent (§6.5), so an empty currency leaf must provision nothing.
   */
  private void reassignPerCurrency(List<Account> subtree, Account target) {
    List<String> currencies =
        subtree.stream().map(Account::currencyCode).distinct().sorted().toList();
    for (String currencyCode : currencies) {
      List<Long> sources =
          subtree.stream()
              .filter(a -> a.currencyCode().equals(currencyCode))
              .map(Account::accountId)
              .toList();
      if (!accountService.hasAnyPostings(sources)) {
        continue;
      }
      Account leaf = currencyLeafService.resolveCurrencyLeaf(target.accountId(), currencyCode);
      postingReassignmentRepository.reassignPostings(sources, leaf.accountId());
    }
  }

  /**
   * The target must be a live category of the subtree root's own type, outside the subtree being
   * deleted, and free of <em>real</em> subcategories once that deletion has happened.
   *
   * <p>Auto-managed currency leaves do not disqualify it: the postings are routed onto those leaves
   * rather than onto the parent, so leaves-only still holds (data-model §5/§6.5). This is validated
   * here rather than trusted from the offer list — the same operation is reachable from a
   * hand-crafted form post and, in time, the MCP surface (CLAUDE.md §1.7).
   */
  private Account requireUsableTarget(long targetLeafId, Account root, List<Long> subtree) {
    Account target =
        accountService
            .findById(targetLeafId)
            .orElseThrow(() -> new IllegalArgumentException("No account with id " + targetLeafId));
    requireLiveCategoryLike(target, root);
    requirePlacedOutsideSubtree(target, subtree);
    return target;
  }

  /** The target is a category in its own right, still live, and of the deleted category's type. */
  private void requireLiveCategoryLike(Account target, Account root) {
    if (target.deletedAt() != null) {
      throw new IllegalArgumentException(
          TARGET + target.name() + "' has itself been deleted — pick a live category");
    }
    if (target.currencyLeaf()) {
      // A currency leaf is an income/expense account and passes every other check, but it is not a
      // category — it is one currency's slot *under* one. Routing onto it would subdivide it into
      // nested currency leaves, a shape data-model §6.5 does not have and the screens never show.
      // The UI never offers one; a hand-crafted post or an MCP call could still name one.
      throw new IllegalArgumentException(
          TARGET
              + target.name()
              + "' is an auto-managed currency leaf — pick the category it sits under");
    }
    if (!target.type().equals(root.type())) {
      throw new IllegalArgumentException(
          TARGET
              + target.name()
              + "' is of type "
              + target.type()
              + " — it must be "
              + root.type()
              + ", the same as '"
              + root.name()
              + "'");
    }
  }

  /**
   * The target survives the deletion and holds no subcategories once it has happened — judged
   * against the post-deletion state, since children inside the subtree are about to be gone. E.g.
   * deleting {@code M&Ms} leaves its parent {@code Sweets} childless, so {@code Sweets} may then
   * receive the postings.
   */
  private void requirePlacedOutsideSubtree(Account target, List<Long> subtree) {
    if (subtree.contains(target.accountId())) {
      throw new IllegalArgumentException(
          TARGET
              + target.name()
              + "' is within the subtree being deleted — it must survive the deletion");
    }
    boolean hasSurvivingSubcategory =
        accountService.findChildrenOf(target.accountId()).stream()
            .filter(child -> !child.currencyLeaf())
            .anyMatch(child -> !subtree.contains(child.accountId()));
    if (hasSurvivingSubcategory) {
      throw new IllegalArgumentException(
          TARGET
              + target.name()
              + "' has subcategories of its own — postings can only land on a leaf");
    }
  }
}
