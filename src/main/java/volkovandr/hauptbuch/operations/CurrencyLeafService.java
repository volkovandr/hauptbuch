package volkovandr.hauptbuch.operations;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.operations.repository.PostingReassignmentRepository;

/**
 * The lazy per-currency-leaf routing of data-model §6.5 (plan stage 7b), as a structural domain
 * operation beside {@link SubdivisionService#subdivideAccount subdivision}. Every account has one
 * currency, so a "category" is a set of per-currency leaves under a common parent (e.g. {@code EUR}
 * and {@code CHF} under {@code Food}); the leaf's currency is <em>determined</em> by the paying
 * account, never chosen (§6.5). This resolves the semantic category the user picked to the concrete
 * leaf a posting in a given currency must hit, creating the leaf on first use.
 *
 * <p>It lives in {@code operations} (called by both the dock and, later, the MCP surface —
 * CLAUDE.md §3) and composes {@code accounts}' leaf creation with the posting-reassignment
 * repository; the dock's commit path calls it once per leg. Every leaf it creates is marked {@code
 * currencyLeaf} (data-model §6.5) — auto-managed, hidden from every picker and the categories
 * screen, and named after the bare currency code (e.g. {@code "EUR"}) rather than the parent's
 * name, since the flag is what marks it. The three cases (§6.5):
 *
 * <ul>
 *   <li><strong>Leaf already in the target currency</strong> → post to it directly;
 *   <li><strong>Leaf in a different currency</strong> → the first spend in a new currency
 *       <em>subdivides</em> it: it becomes a parent, its existing postings move to a same-currency
 *       leaf, and a new leaf is created for the incoming posting's currency;
 *   <li><strong>Already a parent</strong> → find its leaf for the target currency, or create it if
 *       this is the first spend in that currency.
 * </ul>
 */
@Service
public class CurrencyLeafService {

  private final AccountService accountService;
  private final PostingReassignmentRepository postingReassignmentRepository;

  CurrencyLeafService(
      AccountService accountService, PostingReassignmentRepository postingReassignmentRepository) {
    this.accountService = accountService;
    this.postingReassignmentRepository = postingReassignmentRepository;
  }

  /**
   * Resolve the per-currency leaf a posting in {@code currencyCode} must hit for the category the
   * user picked (data-model §6.5), creating leaves as needed. Idempotent: a second spend in a
   * currency already provisioned returns the existing leaf without touching anything.
   *
   * @param categoryId the semantically-picked category — a leaf or an already-subdivided parent
   * @param currencyCode the paying account's currency (the posting's currency)
   * @return the leaf account the posting must be filed under
   * @throws IllegalArgumentException if the category does not exist
   */
  @Transactional
  public Account resolveCurrencyLeaf(long categoryId, String currencyCode) {
    Account category =
        accountService
            .findById(categoryId)
            .orElseThrow(() -> new IllegalArgumentException("No category with id " + categoryId));

    List<Account> children = accountService.findChildrenOf(categoryId);
    if (!children.isEmpty()) {
      return resolveUnderParent(category, children, currencyCode);
    }
    return resolveForLeaf(category, currencyCode);
  }

  /** The picked account is already a parent: use its currency child, or create it on first use. */
  private Account resolveUnderParent(Account parent, List<Account> children, String currencyCode) {
    return children.stream()
        .filter(c -> c.currencyCode().equals(currencyCode))
        .findFirst()
        .orElseGet(() -> currencyLeaf(parent, currencyCode));
  }

  /**
   * The picked account is a leaf: post to it directly if its currency matches, otherwise subdivide
   * it into per-currency leaves (its own postings move to a same-currency leaf, a new leaf is
   * created for the incoming currency).
   */
  private Account resolveForLeaf(Account leaf, String currencyCode) {
    if (leaf.currencyCode().equals(currencyCode)) {
      return leaf;
    }
    // First spend in a new currency: promote the leaf to a parent (§6.5). Its existing postings
    // move
    // to a same-currency child; a leaf with no postings needs no such child (leaves appear only
    // when
    // spent — §6.5), it simply becomes a parent of the new currency leaf.
    if (accountService.hasPostings(leaf.accountId())) {
      Account ownCurrencyLeaf = currencyLeaf(leaf, leaf.currencyCode());
      postingReassignmentRepository.reassignPostings(leaf.accountId(), ownCurrencyLeaf.accountId());
    }
    return currencyLeaf(leaf, currencyCode);
  }

  /** An auto-managed currency leaf under the parent, named after {@code currencyCode} itself. */
  private Account currencyLeaf(Account parent, String currencyCode) {
    return accountService.insertCurrencyLeaf(currencyCode, parent.type(), parent.accountId());
  }
}
