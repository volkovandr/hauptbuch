package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountNode;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.accounts.AccountService;

/**
 * Unit tier (plan §1.5): what a typed category resolves to, with the DB mocked away — an existing
 * leaf matched by path or by bare name, a non-leaf group or an ambiguous name refused, and an
 * unknown {@code Parent - Child} <em>proposed</em> rather than created until the picker's Create
 * control says so (receipt-processing/25, which absorbed transaction-register-ui/14).
 */
@ExtendWith(MockitoExtension.class)
class CategoryResolutionServiceTest {

  private static final long FOOD_ID = 1L;
  private static final long MILK_ID = 2L;
  private static final long CLOTHING_ID = 3L;
  private static final long ADULT_ID = 4L;
  private static final long KIDS_ID = 5L;
  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";
  private static final String EUR = "EUR";
  private static final String FOOD = "Food";
  private static final String MILK = "Milk";
  private static final String CLOTHING = "Clothing";
  private static final String ADULT = "Adult";
  private static final String ADULT_PATH = "Clothing - Adult";
  private static final String SEPARATOR = " - ";
  private static final List<String> MANAGEABLE = List.of(INCOME, EXPENSE);

  @Mock private AccountService accountService;
  @Mock private CategoryService categoryService;

  private CategoryResolutionService resolutionService;

  @BeforeEach
  void setUp() {
    resolutionService = new CategoryResolutionService(accountService, categoryService);
  }

  private static Account account(long id, String name, String type, Long parentId) {
    return new Account(id, name, type, parentId, EUR, null, null, null, null, false, false, false);
  }

  private static AccountNode node(long id, String name, String type, Long parentId) {
    return new AccountNode(account(id, name, type, parentId), parentId == null ? 0 : 1);
  }

  @Test
  void resolveMatchesAnExistingLeafByItsPath() {
    when(accountService.findPostableLeafPaths(MANAGEABLE, SEPARATOR))
        .thenReturn(List.of(new AccountPath(FOOD_ID, FOOD)));

    // Case-insensitive match to the existing top-level leaf — no create, and no caption: the
    // picker input already shows what was picked (receipt-processing/25).
    assertThat(resolutionService.resolveCategory("food", null))
        .isEqualTo(new CategoryResolution.Resolved(FOOD_ID, null));
    verify(categoryService, never()).createCategory(any());
  }

  @Test
  void resolveMatchesAnExistingNestedLeafByItsFullPath() {
    // "Food - Milk" names the existing leaf Milk under Food — it resolves to Milk, never
    // re-creating or subdividing (the datalist offers exactly these composed paths, issue 03).
    when(accountService.findPostableLeafPaths(MANAGEABLE, SEPARATOR))
        .thenReturn(List.of(new AccountPath(MILK_ID, "Food - Milk")));

    assertThat(resolutionService.resolveCategory("Food - Milk", null))
        .isEqualTo(new CategoryResolution.Resolved(MILK_ID, null));
    verify(categoryService, never()).createCategory(any());
  }

  @Test
  void resolveMatchesNestedLeafTypedByItsBareName() {
    // A leaf typed by its bare name (e.g. an edit-mode pre-fill) still resolves, though its offered
    // path is the fuller "Food - Milk".
    when(accountService.findPostableLeafPaths(MANAGEABLE, SEPARATOR))
        .thenReturn(List.of(new AccountPath(MILK_ID, "Food - Milk")));
    when(categoryService.manageableCategories())
        .thenReturn(
            List.of(node(FOOD_ID, FOOD, EXPENSE, null), node(MILK_ID, MILK, EXPENSE, FOOD_ID)));

    assertThat(resolutionService.resolveCategory(MILK, null))
        .isEqualTo(new CategoryResolution.Resolved(MILK_ID, null));
    verify(categoryService, never()).createCategory(any());
  }

  @Test
  void resolveRefusesNonLeafGroup() {
    // Food is a group (its real child Milk makes it non-postable). Selecting it must not silently
    // return the group's id only to have the commit reject the non-leaf posting (issue 03).
    when(accountService.findPostableLeafPaths(MANAGEABLE, SEPARATOR))
        .thenReturn(List.of(new AccountPath(MILK_ID, "Food - Milk")));
    when(categoryService.manageableCategories())
        .thenReturn(
            List.of(node(FOOD_ID, FOOD, EXPENSE, null), node(MILK_ID, MILK, EXPENSE, FOOD_ID)));

    assertThat(resolutionService.resolveCategory(FOOD, null))
        .isInstanceOfSatisfying(
            CategoryResolution.Refused.class,
            refused -> assertThat(refused.message()).contains("group"));
    verify(categoryService, never()).createCategory(any());
  }

  @Test
  void resolveProposesNewChildUnderExistingParentWithoutCreatingIt() {
    // "Food - Milk" where Milk does not exist: the child is only PROPOSED. Creating it on the
    // field's change event turned a typo into a category (transaction-register-ui/14); the picker
    // now names what would be created and waits for an explicit Create.
    when(accountService.findPostableLeafPaths(MANAGEABLE, SEPARATOR))
        .thenReturn(List.of(new AccountPath(FOOD_ID, FOOD)));
    when(categoryService.manageableCategories())
        .thenReturn(List.of(node(FOOD_ID, FOOD, EXPENSE, null)));

    assertThat(resolutionService.resolveCategory("Food - Milk", null))
        .isEqualTo(new CategoryResolution.Pending(MILK, FOOD));
    verify(categoryService, never()).createCategory(any());
  }

  @Test
  void resolveCreatesTheProposedChildOnlyOnTheCreateDecision() {
    // The same text re-posted with the decision the Create button carries: Food is the existing
    // parent, Milk the new child (type inherited), and the caption reports the creation.
    when(accountService.findPostableLeafPaths(MANAGEABLE, SEPARATOR))
        .thenReturn(List.of(new AccountPath(FOOD_ID, FOOD)));
    when(categoryService.manageableCategories())
        .thenReturn(List.of(node(FOOD_ID, FOOD, EXPENSE, null)));
    when(categoryService.createCategory(new CategoryDraft(MILK, EXPENSE, FOOD_ID)))
        .thenReturn(account(MILK_ID, MILK, EXPENSE, FOOD_ID));

    assertThat(
            resolutionService.resolveCategory(
                "Food - Milk", CategoryResolutionService.DECISION_CREATE))
        .isInstanceOfSatisfying(
            CategoryResolution.Resolved.class,
            resolved -> {
              assertThat(resolved.categoryId()).isEqualTo(MILK_ID);
              assertThat(resolved.statusText()).contains(MILK);
            });
  }

  @Test
  void resolveProposesChildUnderParentNamedByItsFullPath() {
    // The datalist offers whole paths, so adding a third level means picking "Clothing - Adult" and
    // typing " - Men" after it. The parent is therefore a PATH, and matching it by bare display
    // name alone refused the very text the picker produces (owner report, 2026-08-30).
    when(accountService.findPostableLeafPaths(MANAGEABLE, SEPARATOR))
        .thenReturn(List.of(new AccountPath(ADULT_ID, ADULT_PATH)));
    when(accountService.findLivePaths(MANAGEABLE, SEPARATOR))
        .thenReturn(
            List.of(new AccountPath(CLOTHING_ID, CLOTHING), new AccountPath(ADULT_ID, ADULT_PATH)));
    when(categoryService.findById(ADULT_ID))
        .thenReturn(Optional.of(account(ADULT_ID, ADULT, EXPENSE, CLOTHING_ID)));

    // The confirm names the parent by its path, so a deep or duplicately named parent is
    // unmistakable.
    assertThat(resolutionService.resolveCategory("Clothing - Adult - Men", null))
        .isEqualTo(new CategoryResolution.Pending("Men", ADULT_PATH));
    verify(categoryService, never()).createCategory(any());
  }

  @Test
  void resolveCreatesUnderParentNamedByItsFullPathEvenWhenThatParentHasChildren() {
    // "Clothing - Adult" already has a child, so it is not a postable leaf and never appears in the
    // leaf paths — adding a second child under it must still work.
    when(accountService.findPostableLeafPaths(MANAGEABLE, SEPARATOR))
        .thenReturn(List.of(new AccountPath(KIDS_ID, "Clothing - Adult - Kids")));
    when(accountService.findLivePaths(MANAGEABLE, SEPARATOR))
        .thenReturn(
            List.of(
                new AccountPath(CLOTHING_ID, CLOTHING),
                new AccountPath(ADULT_ID, ADULT_PATH),
                new AccountPath(KIDS_ID, "Clothing - Adult - Kids")));
    when(categoryService.findById(ADULT_ID))
        .thenReturn(Optional.of(account(ADULT_ID, ADULT, EXPENSE, CLOTHING_ID)));
    when(categoryService.createCategory(new CategoryDraft("Men", EXPENSE, ADULT_ID)))
        .thenReturn(account(MILK_ID, "Men", EXPENSE, ADULT_ID));

    assertThat(
            resolutionService.resolveCategory(
                "Clothing - Adult - Men", CategoryResolutionService.DECISION_CREATE))
        .isEqualTo(new CategoryResolution.Resolved(MILK_ID, "new category: Men"));
  }

  @Test
  void resolveRefusesTheBareParentNameSharedByTwoCategories() {
    // Two "Adult" categories under different parents: the bare name cannot say which, so it is
    // refused rather than guessed at — the full path is how the user picks one.
    when(accountService.findLivePaths(MANAGEABLE, SEPARATOR))
        .thenReturn(
            List.of(
                new AccountPath(ADULT_ID, ADULT_PATH), new AccountPath(KIDS_ID, "Sports - Adult")));
    when(categoryService.manageableCategories())
        .thenReturn(
            List.of(
                node(ADULT_ID, ADULT, EXPENSE, CLOTHING_ID),
                node(KIDS_ID, ADULT, EXPENSE, FOOD_ID)));

    assertThat(resolutionService.resolveCategory("Adult - Men", null))
        .isInstanceOfSatisfying(
            CategoryResolution.Refused.class,
            refused -> assertThat(refused.message()).contains("ambiguous"));
    verify(categoryService, never()).createCategory(any());
  }

  @Test
  void resolveRejectsUnknownBareNameThatIsNotParentChild() {
    assertThat(resolutionService.resolveCategory("Nonexistent", null))
        .isInstanceOfSatisfying(
            CategoryResolution.Refused.class,
            refused -> assertThat(refused.message()).contains("Parent - Child"));
  }

  @Test
  void resolveRejectsParentChildWithUnknownParent() {
    // Refused, not proposed: there is no parent to create the child under.
    assertThat(resolutionService.resolveCategory("Ghost - Milk", null))
        .isInstanceOfSatisfying(
            CategoryResolution.Refused.class,
            refused -> assertThat(refused.message()).contains("No existing category"));
  }

  @Test
  void resolveRejectsAnAmbiguousName() {
    // A same-named income and expense category — the picker cannot guess which was meant.
    when(categoryService.manageableCategories())
        .thenReturn(
            List.of(node(FOOD_ID, "Bonus", INCOME, null), node(MILK_ID, "Bonus", EXPENSE, null)));

    assertThat(resolutionService.resolveCategory("Bonus", null))
        .isInstanceOfSatisfying(
            CategoryResolution.Refused.class,
            refused -> assertThat(refused.message()).contains("ambiguous"));
  }

  @Test
  void resolveRejectsBlankText() {
    assertThat(resolutionService.resolveCategory("  ", null))
        .isInstanceOfSatisfying(
            CategoryResolution.Refused.class,
            refused -> assertThat(refused.message()).contains("required"));
  }
}
