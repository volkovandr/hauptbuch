package volkovandr.hauptbuch.categories;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
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

  /** The catch-all sibling's name when subdividing a posted-to category leaf (data-model §6.5). */
  static final String UNCATEGORIZED = "Uncategorized";

  /** The account types the categories screen manages (data-model §6.5). */
  private static final List<String> MANAGEABLE_TYPES = List.of("income", "expense");

  private final AccountService accountService;
  private final SettingsService settingsService;
  private final SubdivisionService subdivisionService;
  private final DeletionService deletionService;

  CategoryService(
      AccountService accountService,
      SettingsService settingsService,
      SubdivisionService subdivisionService,
      DeletionService deletionService) {
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.subdivisionService = subdivisionService;
    this.deletionService = deletionService;
  }

  /** Find a category by id. */
  public Optional<Account> findById(long accountId) {
    return accountService.findById(accountId).filter(a -> MANAGEABLE_TYPES.contains(a.type()));
  }

  /**
   * The live income and expense accounts (categories) the screen lists and manages, each annotated
   * with its true hierarchy depth and listed depth-first so multi-level trees indent correctly
   * (data-model §5's hierarchy is not limited to two levels).
   */
  public List<AccountNode> manageableCategories() {
    return accountService.findLiveByTypesWithDepth(MANAGEABLE_TYPES);
  }

  /** The live categories of one type that could take a new child, i.e. every category of it. */
  public List<AccountNode> parentOptions(String type) {
    return manageableCategories().stream().filter(n -> type.equals(n.account().type())).toList();
  }

  /**
   * Create a category. A top-level category (no parent) is a single new leaf in the book's base
   * currency. A child of a currently-childless, posted-to leaf triggers subdivision: the leaf gains
   * the requested child and an {@value #UNCATEGORIZED} sibling that absorbs its postings. A child
   * of an already-subdivided (or never-posted) parent is a plain new leaf alongside its siblings.
   *
   * @return the persisted category
   * @throws IllegalArgumentException if the draft violates a rule (blank name, unmanaged type, or a
   *     parent of a different type)
   */
  @Transactional
  public Account createCategory(CategoryDraft draft) {
    validateDraft(draft);
    if (draft.parentId() == null) {
      return accountService.insertLeaf(draft.name(), draft.type(), null, baseCurrency());
    }

    Account parent = requireUsableParent(draft.parentId(), draft.type());
    boolean parentIsLeaf = accountService.findChildrenOf(parent.accountId()).isEmpty();
    if (parentIsLeaf && accountService.hasPostings(parent.accountId())) {
      SubdivisionResult result =
          subdivisionService.subdivideAccount(parent.accountId(), draft.name(), UNCATEGORIZED);
      return result.child();
    }
    return accountService.insertLeaf(
        draft.name(), draft.type(), parent.accountId(), parent.currencyCode());
  }

  /**
   * Resolve the dock's category field (register §3.5, plan stage 7b) to a category id: an existing
   * category is matched by its display name; a {@code Parent - Child} string creates a new leaf
   * under an existing parent (type inherited), reusing {@link #createCategory} — including its
   * implicit subdivision of a posted-to parent leaf. This is the categories module's own logic, so
   * the dock (in {@code operations}) resolves the category through here before committing, keeping
   * {@code operations} free of a {@code categories} dependency (plan stage 7 boundary note).
   *
   * @param text the typed category — an existing category's name, or {@code Parent - Child}
   * @return the resolved (or newly-created) category's id
   * @throws IllegalArgumentException if the text matches nothing and is not a {@code Parent -
   *     Child} with an existing parent, or names an ambiguous category
   */
  @Transactional
  public long resolveCategory(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("A category is required");
    }
    String trimmed = text.strip();

    Optional<Account> existing = findManageableByName(trimmed);
    if (existing.isPresent()) {
      return existing.get().accountId();
    }

    int separator = trimmed.lastIndexOf(" - ");
    if (separator < 0) {
      throw new IllegalArgumentException(
          "Unknown category '"
              + trimmed
              + "' — pick an existing category, or create one as 'Parent - Child'");
    }
    String parentName = trimmed.substring(0, separator).strip();
    String childName = trimmed.substring(separator + 3).strip();
    Account parent =
        findManageableByName(parentName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No existing category '"
                            + parentName
                            + "' to create '"
                            + childName
                            + "' under"));
    return createCategory(new CategoryDraft(childName, parent.type(), parent.accountId()))
        .accountId();
  }

  /**
   * A live manageable category matched by exact display name. Empty if none match; throws if the
   * name is ambiguous (e.g. a same-named income and expense category) — the dock cannot guess
   * which.
   */
  private Optional<Account> findManageableByName(String name) {
    List<Account> matches =
        manageableCategories().stream()
            .map(AccountNode::account)
            .filter(a -> a.name().equalsIgnoreCase(name))
            .toList();
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          "Category '" + name + "' is ambiguous — more than one category has that name");
    }
    return matches.stream().findFirst();
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
    requireManageable(accountId);
    accountService.renameAccount(accountId, name);
  }

  /**
   * The live leaf categories that may receive a deleted subtree's postings: same type as the
   * category being deleted, a leaf (postings land only on leaves, data-model §5), and outside the
   * subtree itself (a target within the subtree would be deleted too). The leaf-only picker for the
   * delete panel (plan stage 6c).
   *
   * <p>"Leaf" is judged against the state <em>after</em> the deletion: a node whose only children
   * are inside the subtree becomes a leaf once they are gone, so it is a valid target. E.g.
   * deleting {@code M&Ms} leaves {@code Sweets} childless — {@code Sweets} may receive the
   * postings.
   */
  public List<AccountNode> deleteTargetOptions(long subtreeRootId) {
    Account root = requireManageable(subtreeRootId);
    Set<Long> subtree = Set.copyOf(accountService.findSubtreeAccountIds(subtreeRootId));
    List<AccountNode> survivors =
        manageableCategories().stream()
            .filter(n -> root.type().equals(n.account().type()))
            .filter(n -> !subtree.contains(n.account().accountId()))
            .toList();
    // A survivor is still a parent only if one of its children also survives the deletion.
    Set<Long> survivingParents =
        survivors.stream()
            .map(n -> n.account().parentId())
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    return survivors.stream()
        .filter(n -> !survivingParents.contains(n.account().accountId()))
        .toList();
  }

  /**
   * Delete a category and its whole subtree (plan stage 6c), reassigning every posting under it
   * onto the chosen surviving {@code targetLeafId}. Unlike an account (closed/reopened), a category
   * is truly removed — the mechanical cascade and target validation live in {@link
   * DeletionService}.
   *
   * @throws IllegalArgumentException if the category is not one this screen manages, or the target
   *     is invalid (not a leaf, or within the subtree being deleted)
   */
  @Transactional
  public void deleteCategory(long accountId, long targetLeafId) {
    requireManageable(accountId);
    deletionService.deleteCategory(accountId, targetLeafId);
  }

  private void validateDraft(CategoryDraft draft) {
    if (draft.name() == null || draft.name().isBlank()) {
      throw new IllegalArgumentException("A category needs a name");
    }
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
