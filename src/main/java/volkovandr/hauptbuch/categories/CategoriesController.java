package volkovandr.hauptbuch.categories;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
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

  private final CategoryService categoryService;

  CategoriesController(CategoryService categoryService) {
    this.categoryService = categoryService;
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
