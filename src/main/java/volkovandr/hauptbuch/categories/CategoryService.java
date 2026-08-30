package volkovandr.hauptbuch.categories;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.accounts.ReservedNamePrefix;
import volkovandr.hauptbuch.categories.repository.CategoryAiConfigRepository;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.DeletionService;
import volkovandr.hauptbuch.operations.SubdivisionResult;
import volkovandr.hauptbuch.operations.SubdivisionService;

/**
 * Category management (plan stage 6b) — the owning home of the categories screen. Categories are
 * backed by {@code income}/{@code expense} accounts (data-model §6.5); this module owns the
 * category-specific logic that keeps them consistent, distinct from logic that applies to all
 * accounts ({@code accounts} module, CLAUDE.md §3).
 *
 * <p>Creating a category is either a plain insert or — when the chosen parent is currently a
 * posted-to leaf — a {@link SubdivisionService#subdivideAccount subdivision}: the parent gains the
 * requested child <em>and</em> an {@value #UNCATEGORIZED} sibling that absorbs its existing
 * postings, becoming a pure rollup (leaves-only, data-model §5).
 */
@Service
public class CategoryService {

  private static final Logger LOG = LoggerFactory.getLogger(CategoryService.class);

  /** The catch-all sibling's name when subdividing a posted-to category leaf (data-model §6.5). */
  static final String UNCATEGORIZED = "Uncategorized";

  /** The account types the categories screen manages (data-model §6.5). */
  static final List<String> MANAGEABLE_TYPES = List.of("income", "expense");

  private final AccountService accountService;
  private final SettingsService settingsService;
  private final SubdivisionService subdivisionService;
  private final DeletionService deletionService;
  private final CategoryAiConfigRepository categoryAiConfigRepository;

  CategoryService(
      AccountService accountService,
      SettingsService settingsService,
      SubdivisionService subdivisionService,
      DeletionService deletionService,
      CategoryAiConfigRepository categoryAiConfigRepository) {
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.subdivisionService = subdivisionService;
    this.deletionService = deletionService;
    this.categoryAiConfigRepository = categoryAiConfigRepository;
  }

  /** Find a category by id. */
  public Optional<Account> findById(long accountId) {
    return accountService.findById(accountId).filter(a -> MANAGEABLE_TYPES.contains(a.type()));
  }

  /**
   * The live income and expense accounts (categories) the screen lists and manages, each annotated
   * with its true hierarchy depth and listed depth-first so multi-level trees indent correctly
   * (data-model §5's hierarchy is not limited to two levels). Excludes {@code
   * CurrencyLeafService}'s auto-managed per-currency leaves (data-model §6.5) — those are never
   * individually visible or selectable; they are carried along automatically with whichever
   * category they sit under.
   */
  public List<AccountNode> manageableCategories() {
    return accountService.findLiveByTypesWithDepth(MANAGEABLE_TYPES).stream()
        .filter(n -> !n.account().currencyLeaf())
        .toList();
  }

  /** The live categories of one type that could take a new child, i.e. every category of it. */
  public List<AccountNode> parentOptions(String type) {
    return manageableCategories().stream().filter(n -> type.equals(n.account().type())).toList();
  }

  /**
   * Create a category. A top-level category (no parent) is a single new leaf in the book's base
   * currency. A child of a parent with no <em>real</em> children yet (data-model §6.5's
   * auto-managed currency leaves don't count) that is either directly posted-to or already has
   * currency leaves of its own triggers subdivision: the parent gains the requested child and an
   * {@value #UNCATEGORIZED} sibling that absorbs its existing postings and currency leaves alike. A
   * child of an already-subdivided (or never-posted) parent is a plain new leaf alongside its
   * siblings.
   *
   * @return the persisted category
   * @throws IllegalArgumentException if the draft violates a rule (blank name, unmanaged type, or a
   *     parent of a different type)
   */
  @Transactional
  public Account createCategory(CategoryDraft draft) {
    validateDraft(draft);
    Account created = insertCategory(draft);
    // The "why" beside the backing account's own "Account opened" line (observability/02).
    LOG.info("Category created: id={}, name={}", created.accountId(), created.name());
    return created;
  }

  /** The mechanical half of {@link #createCategory}: plain leaf, or a subdivision of the parent. */
  private Account insertCategory(CategoryDraft draft) {
    if (draft.parentId() == null) {
      return accountService.insertLeaf(draft.name(), draft.type(), null, baseCurrency());
    }

    Account parent = requireUsableParent(draft.parentId(), draft.type());
    List<Account> children = accountService.findChildrenOf(parent.accountId());
    boolean parentHasRealChildren = children.stream().anyMatch(c -> !c.currencyLeaf());
    boolean parentHasCurrencyLeaves = children.stream().anyMatch(Account::currencyLeaf);
    boolean needsSubdivision =
        !parentHasRealChildren
            && (parentHasCurrencyLeaves || accountService.hasPostings(parent.accountId()));
    if (needsSubdivision) {
      SubdivisionResult result =
          subdivisionService.subdivideAccount(parent.accountId(), draft.name(), UNCATEGORIZED);
      return result.child();
    }
    return accountService.insertLeaf(
        draft.name(), draft.type(), parent.accountId(), parent.currencyCode());
  }

  /**
   * Rename a category's freely-editable field: display name. Type, currency, and parent are
   * immutable through the UI — the same stance {@code accounts} takes on real accounts.
   *
   * @throws IllegalArgumentException if the category does not exist, is not one this screen
   *     manages, or the name is blank
   */
  @Transactional
  public void renameCategory(long accountId, String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A category needs a name");
    }
    ReservedNamePrefix.check(name);
    requireManageable(accountId);
    accountService.renameAccount(accountId, name);
  }

  /**
   * The delete panel's state for one category (plan stage 6c): whether the deletion needs a
   * reassignment target at all, and the live leaves that may receive the subtree's postings.
   *
   * <p>A target is needed only when some posting — live or voided — has ever hit the subtree; a
   * category nothing was ever filed under deletes outright (issue category-management/06).
   *
   * <p>An offerable target has the same type as the category being deleted, has no
   * <em>subcategories</em> of its own, and lies outside the subtree itself (a target within the
   * subtree would be deleted too). "No subcategories" is judged against the state <em>after</em>
   * the deletion — e.g. deleting {@code M&Ms} leaves {@code Sweets} childless, so {@code Sweets}
   * may receive the postings.
   *
   * <p>{@code CurrencyLeafService}'s hidden auto-managed currency leaves are not subcategories and
   * do not disqualify a target (issue category-management/05): the deletion routes each posting
   * onto the target's leaf for its own currency, so leaves-only still holds and the currency is
   * preserved (data-model §6.5). Counting them would exclude every category that has ever been
   * posted to, emptying the list. They are still never offered under their own identity — they are
   * excluded from {@link #manageableCategories()}. This is the same "real children" test the
   * <em>create</em> path applies when deciding whether a parent needs subdividing; the two must
   * agree.
   */
  public CategoryDeletePanel deletePanel(long subtreeRootId) {
    Account root = requireManageable(subtreeRootId);
    List<Long> subtreeIds = accountService.findSubtreeAccountIds(subtreeRootId);
    Set<Long> subtree = Set.copyOf(subtreeIds);
    List<AccountNode> targets =
        manageableCategories().stream()
            .filter(n -> root.type().equals(n.account().type()))
            .filter(n -> !subtree.contains(n.account().accountId()))
            .filter(n -> hasNoSubcategoriesAfterDeletion(n.account().accountId(), subtree))
            .toList();
    return new CategoryDeletePanel(accountService.hasAnyPostings(subtreeIds), targets);
  }

  /**
   * Whether an account would be free of real subcategories after the given subtree is deleted —
   * counting live children that are neither about to be deleted with it nor auto-managed currency
   * leaves (data-model §6.5).
   */
  private boolean hasNoSubcategoriesAfterDeletion(long accountId, Set<Long> deletedSubtree) {
    return accountService.findChildrenOf(accountId).stream()
        .filter(child -> !child.currencyLeaf())
        .allMatch(child -> deletedSubtree.contains(child.accountId()));
  }

  /**
   * Delete a category and its whole subtree (plan stage 6c), reassigning every posting under it
   * onto the chosen surviving {@code targetLeafId}. Unlike an account (closed/reopened), a category
   * is truly removed — the mechanical cascade and target validation live in {@link
   * DeletionService}, including the rule that a postingless subtree needs no target at all ({@code
   * targetLeafId} {@code null}).
   *
   * @throws IllegalArgumentException if the category is not one this screen manages, the target is
   *     absent while the subtree carries postings, or the target is invalid (not a live category of
   *     the same type, one with subcategories of its own, or one within the subtree being deleted)
   */
  @Transactional
  public void deleteCategory(long accountId, Long targetLeafId) {
    Account category = requireManageable(accountId);
    // Capture the subtree before the deletion soft-deletes it (findSubtreeAccountIds is scoped to
    // live rows), so the AI-vocabulary config rows for the whole subtree can be swept with it
    // (data-model §13.3). The reassignment target keeps its own config — it survives the deletion.
    List<Long> subtree = accountService.findSubtreeAccountIds(accountId);
    deletionService.deleteCategory(accountId, targetLeafId);
    categoryAiConfigRepository.deleteByAccountIds(subtree);
    // Logged only once the deletion has actually happened — a refused delete (no target for a
    // posted-to subtree) must not leave a permanent "Category deleted" behind it (CLAUDE.md §5).
    LOG.info("Category deleted: id={}, name={}", category.accountId(), category.name());
  }

  private void validateDraft(CategoryDraft draft) {
    if (draft.name() == null || draft.name().isBlank()) {
      throw new IllegalArgumentException("A category needs a name");
    }
    ReservedNamePrefix.check(draft.name());
    if (!MANAGEABLE_TYPES.contains(draft.type())) {
      throw new IllegalArgumentException(
          "Category type must be one of " + MANAGEABLE_TYPES + ", not '" + draft.type() + "'");
    }
  }

  private Account requireManageable(long accountId) {
    return findById(accountId)
        .orElseThrow(
            () -> new IllegalArgumentException("No manageable category with id " + accountId));
  }

  private Account requireUsableParent(long parentId, String childType) {
    Account parent = requireManageable(parentId);
    if (!parent.type().equals(childType)) {
      throw new IllegalArgumentException(
          "Parent '" + parent.name() + "' is a " + parent.type() + " category, not " + childType);
    }
    return parent;
  }

  private String baseCurrency() {
    return settingsService
        .baseCurrency()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Base currency is not set; categories cannot be created until first-run "
                        + "setup sets it (data-model §3.8)"));
  }
}
