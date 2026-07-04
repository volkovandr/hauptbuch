package volkovandr.hauptbuch.categories;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;
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

  CategoryService(
      AccountService accountService,
      SettingsService settingsService,
      SubdivisionService subdivisionService) {
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.subdivisionService = subdivisionService;
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
