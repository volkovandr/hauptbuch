package volkovandr.hauptbuch.categories;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.accounts.AccountService;

/**
 * Resolves a typed category against the taxonomy (register §3.5, plan stage 7b) — the single home
 * of the rule, called by the one {@code /categories/resolve} endpoint every picker in the app posts
 * to: the register's simple dock, the register split's lines, and the receipts post-process
 * editor's lines.
 *
 * <p>It is its own service, beside {@link CategoryService} rather than inside it, for the reason
 * {@code debts} splits {@code PersonResolutionService} off {@code PersonService}: resolution is a
 * decision — match, propose, refuse — over the taxonomy that management owns, and the pending
 * create-confirm it gained (receipt-processing/25) is logic in its own right. Creating the leaf is
 * still {@link CategoryService#createCategory} and nothing else.
 */
@Service
public class CategoryResolutionService {

  /** The decision value the pending create-confirm button re-posts (receipt-processing/25). */
  public static final String DECISION_CREATE = "CREATE";

  /**
   * The Category field's hierarchy separator (register §3.5): {@code Food - Milk}. The same string
   * a composed leaf path is displayed with and a new {@code Parent - Child} leaf is created from.
   */
  private static final String PATH_SEPARATOR = " - ";

  private final AccountService accountService;
  private final CategoryService categoryService;

  CategoryResolutionService(AccountService accountService, CategoryService categoryService) {
    this.accountService = accountService;
    this.categoryService = categoryService;
  }

  /**
   * Resolve the dock's category field (register §3.5, plan stage 7b) into what the picker should
   * render (see {@link CategoryResolution}). In order: an existing posting leaf matched by its full
   * {@code Parent - Child} path (the paths the datalist offers, issue 03); an existing leaf matched
   * by its bare display name (an edit-mode pre-fill, or a leaf typed directly); or a {@code Parent
   * - Child} string naming a new leaf under an existing parent, which is <em>proposed</em> and
   * created only once {@code decision} says so — reusing {@link #createCategory}, including its
   * implicit subdivision of a posted-to parent leaf.
   *
   * <p>Creation is never a side effect of leaving the field (receipt-processing/25): a typo like
   * {@code Food - Mlik} used to become a category the moment the field lost focus. The picker now
   * names what would be created and waits, exactly as reviving a soft-deleted person does
   * (data-model §7).
   *
   * <p>A name that resolves to a <em>non-leaf</em> (a group with real subcategories) is refused: a
   * posting lands only on leaves (data-model §5), so silently returning the group's id only to have
   * the commit reject it read as a broken Save (issue 03). This is the categories module's own
   * logic, so the dock (in {@code operations}) resolves the category through here before
   * committing, keeping {@code operations} free of a {@code categories} dependency (plan stage 7
   * boundary note).
   *
   * @param text the typed category — an existing leaf's path or name, or {@code Parent - Child}
   *     <p>Deliberately <em>not</em> {@code @Transactional}: the one write is {@link
   *     CategoryService#createCategory}, which owns its own boundary (its subdivision is atomic
   *     there). A transaction here would span the {@code catch} below — a create that fails
   *     validation (a reserved child name, say) marks the shared transaction rollback-only, and
   *     swallowing its exception to return {@link CategoryResolution.Refused} would then blow up as
   *     an {@code UnexpectedRollbackException} at commit instead of showing the message.
   * @param decision {@link #DECISION_CREATE} once the user has confirmed the proposed create, or
   *     {@code null} when they have not been asked (or had nothing to decide)
   */
  public CategoryResolution resolveCategory(String text, String decision) {
    if (text == null || text.isBlank()) {
      return new CategoryResolution.Refused("A category is required");
    }
    String trimmed = text.strip();
    try {
      List<AccountPath> leaves =
          accountService.findPostableLeafPaths(CategoryService.MANAGEABLE_TYPES, PATH_SEPARATOR);

      // A leaf matched by the full Parent - Child path the datalist offers (also a top-level leaf's
      // bare name), else by its bare name; failing both, a Parent - Child string names a new leaf.
      OptionalLong byPath = matchLeafByPath(trimmed, leaves);
      if (byPath.isPresent()) {
        return new CategoryResolution.Resolved(byPath.getAsLong(), null);
      }
      OptionalLong byName = matchLeafByName(trimmed, leaves);
      if (byName.isPresent()) {
        return new CategoryResolution.Resolved(byName.getAsLong(), null);
      }
      return proposeOrCreateLeaf(trimmed, decision);
    } catch (IllegalArgumentException e) {
      return new CategoryResolution.Refused(e.getMessage());
    }
  }

  /** A posting leaf whose full {@code Parent - Child} path equals the text; empty if none match. */
  private OptionalLong matchLeafByPath(String path, List<AccountPath> leaves) {
    List<AccountPath> matches =
        leaves.stream().filter(l -> l.path().equalsIgnoreCase(path)).toList();
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          "Category '" + path + "' is ambiguous — more than one category has that path");
    }
    return matches.isEmpty() ? OptionalLong.empty() : OptionalLong.of(matches.get(0).accountId());
  }

  /**
   * A leaf matched by its bare display name (an edit-mode pre-fill, or a nested leaf typed
   * directly); empty if the name matches nothing. A name that resolves to a non-leaf group is
   * refused — a posting lands only on leaves (data-model §5), so a group must be narrowed first.
   */
  private OptionalLong matchLeafByName(String name, List<AccountPath> leaves) {
    Optional<Account> existing = findManageableByName(name);
    if (existing.isEmpty()) {
      return OptionalLong.empty();
    }
    long accountId = existing.get().accountId();
    if (leaves.stream().anyMatch(l -> l.accountId() == accountId)) {
      return OptionalLong.of(accountId);
    }
    throw new IllegalArgumentException(
        "Category '"
            + existing.get().name()
            + "' is a group — pick one of its subcategories (e.g. '"
            + existing.get().name()
            + " - …')");
  }

  /**
   * Propose a new leaf under an existing parent from a {@code Parent - Child} string — creating it
   * only when {@code decision} is the confirmation the picker's Create control re-posts.
   */
  private CategoryResolution proposeOrCreateLeaf(String text, String decision) {
    int separator = text.lastIndexOf(PATH_SEPARATOR);
    if (separator < 0) {
      throw new IllegalArgumentException(
          "Unknown category '"
              + text
              + "' — pick an existing category, or create one as 'Parent - Child'");
    }
    String parentName = text.substring(0, separator).strip();
    String childName = text.substring(separator + PATH_SEPARATOR.length()).strip();
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
    if (!DECISION_CREATE.equals(decision)) {
      return new CategoryResolution.Pending(childName, parent.name());
    }
    Account created =
        categoryService.createCategory(
            new CategoryDraft(childName, parent.type(), parent.accountId()));
    return new CategoryResolution.Resolved(created.accountId(), "new category: " + created.name());
  }

  /**
   * A live manageable category matched by exact display name. Empty if none match; throws if the
   * name is ambiguous (e.g. a same-named income and expense category) — the dock cannot guess
   * which.
   */
  private Optional<Account> findManageableByName(String name) {
    List<Account> matches =
        categoryService.manageableCategories().stream()
            .map(AccountNode::account)
            .filter(a -> a.name().equalsIgnoreCase(name))
            .toList();
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          "Category '" + name + "' is ambiguous — more than one category has that name");
    }
    return matches.stream().findFirst();
  }
}
