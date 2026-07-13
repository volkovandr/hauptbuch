package volkovandr.hauptbuch.categories;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.TransferTarget;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The categories screen (plan stage 6b): the list of income and expense categories, the
 * create-category form — top-level or as a child of an existing category, which may trigger
 * subdivision (data-model §6.5) — and the per-category rename page.
 *
 * <p>Lives in the {@code categories} module, not {@code web}: feature screens' controllers belong
 * to their feature module (CLAUDE.md §3). Standard server-rendered forms, redirect after POST; no
 * bespoke JS.
 */
@Controller
class CategoriesController {

  private static final String BASE_PATH = "/categories";
  private static final String LIST_VIEW = "categories";
  private static final String EDIT_VIEW = "category-edit";
  private static final String REDIRECT_TO_LIST = "redirect:" + BASE_PATH;
  private static final String INCOME = "income";
  private static final String EXPENSE = "expense";

  /** Model keys for the {@code categoryResolved} fragment, shared by both resolve branches. */
  private static final String RESOLVED_ID = "categoryId";

  private static final String RESOLVED_NAME = "categoryName";
  private static final String RESOLVED_TYPE = "categoryType";
  private static final String RESOLVED_DIRECTION = "transferDirection";
  private static final String RESOLVED_ERROR = "error";

  /**
   * htmx response header the transfer branch raises so the dock recomputes its amount fields
   * (register §3.8a, plan stage 7d.3): a transfer's counterpart currency is fixed by the resolved
   * account, not the currency selector, so the reveal cannot ride the selector's own change — the
   * dock listens for this event and re-fetches {@code /register/currency-fields} once the resolved
   * counterpart (its id + direction) has landed.
   *
   * <p>It is raised via {@code HX-Trigger-After-Swap}, not plain {@code HX-Trigger}: the latter
   * fires <em>before</em> this response is swapped in, so the follow-up recompute would serialise
   * the form <em>without</em> the just-resolved {@code categoryId}/{@code transferDirection} and
   * see no transfer (leaving the currency selector on the base currency). After-swap fires once the
   * hidden inputs are in the DOM.
   */
  private static final String TRIGGER_AFTER_SWAP = "HX-Trigger-After-Swap";

  private static final String COUNTERPART_RESOLVED = "counterpart-resolved";

  private final CategoryService categoryService;
  private final AccountService accountService;

  CategoriesController(CategoryService categoryService, AccountService accountService) {
    this.categoryService = categoryService;
    this.accountService = accountService;
  }

  /** The category list plus the create-category form. */
  @GetMapping(BASE_PATH)
  String categories(Model model) {
    List<AccountNode> categories = categoryService.manageableCategories();
    model.addAttribute(
        "incomeCategories",
        categories.stream().filter(n -> INCOME.equals(n.account().type())).toList());
    model.addAttribute(
        "expenseCategories",
        categories.stream().filter(n -> EXPENSE.equals(n.account().type())).toList());
    model.addAttribute("incomeParentOptions", categoryService.parentOptions(INCOME));
    model.addAttribute("expenseParentOptions", categoryService.parentOptions(EXPENSE));
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Categories · Hauptbuch");
    return LIST_VIEW;
  }

  /**
   * Create a category, top-level or under an existing one of the same type. Choosing a
   * currently-posted-to leaf as the parent subdivides it: the leaf gains the requested child and an
   * {@code Uncategorized} sibling that absorbs its postings (data-model §6.5).
   */
  @PostMapping(BASE_PATH)
  String createCategory(
      @RequestParam String name,
      @RequestParam String type,
      @RequestParam(required = false) Long parentId) {
    categoryService.createCategory(new CategoryDraft(name, type, parentId));
    return REDIRECT_TO_LIST;
  }

  /**
   * Resolve the Category field's counterpart (register §3.5, plan stage 7b/7d.3): an existing
   * category by name, a new {@code Parent - Child} leaf, or a <em>transfer target</em> ({@code To →
   * <account>} / {@code From ← <account>}) routing the counter-leg to a real own account. Returns
   * the {@code categoryResolved} fragment — the hidden id the caller commits, plus a status; a
   * transfer additionally carries the {@code transferDirection} the commit signs the funding leg by
   * (register §3.8) and raises the {@link #COUNTERPART_RESOLVED} header so the dock reveals the
   * counterpart-amount field for a cross-currency transfer. On a bad value it returns the fragment
   * carrying the error and no id, so nothing can commit an unresolved counterpart.
   *
   * <p>Parameterised so both the simple dock and the split panel's per-line pickers share it (plan
   * stage 7c.2): {@code fieldName} names the hidden id input ({@code categoryId} for the dock,
   * {@code lineCategoryId} for a split line), and {@code typeFieldName}, when present, adds a
   * second hidden input carrying the resolved category's {@code income}/{@code expense} type — the
   * split line needs it to sign its contribution in the live remaining/direction readout.
   *
   * <p>A split line's resolve request carries <em>every</em> line's {@code categoryText} (htmx
   * includes the whole panel form), so {@code index} selects which one is being resolved —
   * otherwise the values arrive joined ("Sweets,Non-Sweets,") and no category matches. The dock
   * posts a single value at the default index 0.
   *
   * <p>Lives here, not in the dock's {@code operations} controller: creating a category is this
   * module's logic, and {@code operations → categories} would close a module cycle (plan stage 7
   * boundary note). Resolving a transfer target is an {@code accounts} lookup, which this module
   * may reach; the browser bridges the two — it resolves here, then commits to {@code operations}
   * with the returned id (and direction).
   */
  @PostMapping("/categories/resolve")
  String resolveCategory(
      @RequestParam List<String> categoryText,
      @RequestParam(defaultValue = "0") int index,
      @RequestParam(defaultValue = RESOLVED_ID) String fieldName,
      @RequestParam(required = false) String typeFieldName,
      Model model,
      HttpServletResponse response) {
    String text = index >= 0 && index < categoryText.size() ? categoryText.get(index) : "";
    Optional<TransferTarget.Parsed> transfer = TransferTarget.parse(text);
    if (transfer.isPresent()) {
      resolveTransfer(transfer.get(), model, response);
    } else {
      resolveCategoryText(text, model);
    }
    model.addAttribute("fieldName", fieldName);
    model.addAttribute("typeFieldName", typeFieldName);
    return "fragments/entry-dock :: categoryResolved(categoryId=${categoryId},"
        + " categoryName=${categoryName}, error=${error}, fieldName=${fieldName},"
        + " typeFieldName=${typeFieldName}, categoryType=${categoryType},"
        + " transferDirection=${transferDirection})";
  }

  /** Resolve a plain category name or {@code Parent - Child} to its (existing or new) leaf id. */
  private void resolveCategoryText(String text, Model model) {
    try {
      long categoryId = categoryService.resolveCategory(text);
      Account category =
          categoryService
              .findById(categoryId)
              .orElseThrow(() -> new IllegalStateException("resolved category vanished"));
      model.addAttribute(RESOLVED_ID, categoryId);
      model.addAttribute(RESOLVED_NAME, category.name());
      model.addAttribute(RESOLVED_TYPE, category.type());
      model.addAttribute(RESOLVED_ERROR, null);
    } catch (IllegalArgumentException e) {
      model.addAttribute(RESOLVED_ID, "");
      model.addAttribute(RESOLVED_NAME, null);
      model.addAttribute(RESOLVED_TYPE, "");
      model.addAttribute(RESOLVED_ERROR, e.getMessage());
    }
    model.addAttribute(RESOLVED_DIRECTION, null);
  }

  /**
   * Resolve a transfer target (register §3.5/§3.8): the counter-leg is the named own account, so
   * its id fills the same hidden {@code categoryId} slot the commit reads, and {@code
   * transferDirection} marks it a transfer. Raises {@link #COUNTERPART_RESOLVED} so the dock
   * recomputes its amount fields against the counterpart account's currency (a cross-currency
   * transfer reveals the extra field). An unresolved/ambiguous name is refused, exactly like a bad
   * category.
   */
  private void resolveTransfer(
      TransferTarget.Parsed transfer, Model model, HttpServletResponse response) {
    Optional<Account> account = accountService.findOwnAccountByName(transfer.accountName());
    if (account.isEmpty()) {
      model.addAttribute(RESOLVED_ID, "");
      model.addAttribute(RESOLVED_NAME, null);
      model.addAttribute(RESOLVED_TYPE, "");
      model.addAttribute(RESOLVED_DIRECTION, null);
      model.addAttribute(RESOLVED_ERROR, "No account named " + transfer.accountName());
      return;
    }
    model.addAttribute(RESOLVED_ID, account.get().accountId());
    model.addAttribute(
        RESOLVED_NAME, TransferTarget.option(transfer.direction(), account.get().name()));
    model.addAttribute(RESOLVED_TYPE, "");
    model.addAttribute(RESOLVED_DIRECTION, transfer.direction().name());
    model.addAttribute(RESOLVED_ERROR, null);
    response.setHeader(TRIGGER_AFTER_SWAP, COUNTERPART_RESOLVED);
  }

  /** The edit page for one category: rename only — type, currency, and parent are fixed. */
  @GetMapping("/categories/{accountId}")
  String editCategory(@PathVariable long accountId, Model model) {
    Account category =
        categoryService
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("No category with id " + accountId));
    model.addAttribute("category", category);
    model.addAttribute("deleteTargets", categoryService.deleteTargetOptions(accountId));
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", category.name() + " · Hauptbuch");
    return EDIT_VIEW;
  }

  /** Rename the category. */
  @PostMapping("/categories/{accountId}")
  String renameCategory(@PathVariable long accountId, @RequestParam String name) {
    categoryService.renameCategory(accountId, name);
    return REDIRECT_TO_LIST;
  }

  /**
   * Delete the category and its whole subtree (plan stage 6c), moving every posting under it onto
   * the chosen surviving leaf. Truly removed — no reopen, unlike an account's close.
   */
  @PostMapping("/categories/{accountId}/delete")
  String deleteCategory(@PathVariable long accountId, @RequestParam long targetLeafId) {
    categoryService.deleteCategory(accountId, targetLeafId);
    return REDIRECT_TO_LIST;
  }
}
