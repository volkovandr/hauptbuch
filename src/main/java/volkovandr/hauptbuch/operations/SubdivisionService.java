package volkovandr.hauptbuch.operations;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.operations.repository.PostingReassignmentRepository;

/**
 * The subdivision domain operation (plan stage 6b, data-model §5/§6.5): "a leaf was a leaf, now it
 * has children." Generic over any account type — {@code categories} is its first caller (turning a
 * posted-to category leaf into a parent the moment it needs its first real child), but the same
 * leaves-only mechanics apply to any account (data-model §5's framing, and {@code AccountService}'s
 * existing forward references to this operation).
 *
 * <p>Not a UI-visible action of its own: the caller decides <em>when</em> to subdivide (e.g. "the
 * chosen parent is currently a posted-to leaf") and supplies the catch-all's name (data-model
 * §6.5's "Uncategorized" for categories; a different name may suit other account types). This
 * service only does the mechanical part — create the child(ren), move the postings, and (data-model
 * §6.5) re-parent any auto-managed currency leaves the leaf had already spawned — atomically.
 * Currency leaves are never counted as "real" children: a leaf that has only ever spawned currency
 * leaves (never a genuine subcategory) is still treated as a leaf here, exactly as {@code
 * CategoryService} treats it when deciding whether to subdivide (plan stage 7d.1 follow-up).
 */
@Service
public class SubdivisionService {

  private final AccountService accountService;
  private final PostingReassignmentRepository postingReassignmentRepository;

  SubdivisionService(
      AccountService accountService, PostingReassignmentRepository postingReassignmentRepository) {
    this.accountService = accountService;
    this.postingReassignmentRepository = postingReassignmentRepository;
  }

  /**
   * Subdivide a leaf: create {@code childName} as a new child, same type and currency as the leaf
   * being subdivided. If the leaf carries any live postings, or already has auto-managed currency
   * leaves of its own (data-model §6.5), also create {@code catchAllName} as a sibling child: the
   * leaf's own postings move onto it, and its existing currency leaves are re-parented onto it
   * unchanged (they already hold the real postings, so nothing needs reassigning) — the leaf itself
   * becomes a pure rollup (leaves-only, data-model §5).
   *
   * @param leafId the account to subdivide; must currently have no <em>real</em> children (currency
   *     leaves don't count)
   * @param childName the new child's name
   * @param catchAllName the catch-all sibling's name, created only if there is anything to move
   *     onto it
   * @throws IllegalArgumentException if {@code leafId} does not exist or already has a real child
   */
  @Transactional
  public SubdivisionResult subdivideAccount(long leafId, String childName, String catchAllName) {
    Account leaf = requireLeaf(leafId);

    Account child = accountService.insertLeaf(childName, leaf.type(), leafId, leaf.currencyCode());

    List<Account> currencyLeaves =
        accountService.findChildrenOf(leafId).stream().filter(Account::currencyLeaf).toList();
    boolean hasDirectPostings = accountService.hasPostings(leafId);
    if (!hasDirectPostings && currencyLeaves.isEmpty()) {
      return new SubdivisionResult(child, null);
    }

    Account catchAll =
        accountService.insertLeaf(catchAllName, leaf.type(), leafId, leaf.currencyCode());
    if (hasDirectPostings) {
      postingReassignmentRepository.reassignPostings(leafId, catchAll.accountId());
    }
    for (Account currencyLeaf : currencyLeaves) {
      accountService.reparent(currencyLeaf.accountId(), catchAll.accountId());
    }
    return new SubdivisionResult(child, catchAll);
  }

  private Account requireLeaf(long accountId) {
    Account account =
        accountService
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("No account with id " + accountId));
    boolean hasRealChildren =
        accountService.findChildrenOf(accountId).stream().anyMatch(c -> !c.currencyLeaf());
    if (hasRealChildren) {
      throw new IllegalArgumentException(
          "Account '" + account.name() + "' already has children — it is not a leaf to subdivide");
    }
    return account;
  }
}
