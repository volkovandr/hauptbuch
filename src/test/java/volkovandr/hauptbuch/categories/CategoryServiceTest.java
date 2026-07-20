package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.DeletionService;
import volkovandr.hauptbuch.operations.SubdivisionResult;
import volkovandr.hauptbuch.operations.SubdivisionService;

/**
 * Unit tier (plan §1.5): category creation's decision logic with the DB mocked away — a top-level
 * category is a base-currency leaf, a child of a posted leaf (or one whose only children are
 * auto-managed currency leaves, data-model §6.5, plan stage 7d.1 follow-up) triggers subdivision, a
 * child of a childless leaf or an already-subdivided parent is a plain insert, and the
 * type/parent-type validation rejects bad input before any write. Also covers the currency-leaf
 * filtering of {@link CategoryService#manageableCategories()} and {@link
 * CategoryService#deleteTargetOptions(long)}.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

  private static final long FOOD_ID = 1L;
  private static final long MILK_ID = 2L;
  private static final long UNCATEGORIZED_ID = 3L;
  private static final long CURRENCY_LEAF_EUR_ID = 4L;
  private static final long CURRENCY_LEAF_CHF_ID = 5L;
  private static final long FUEL_ID = 6L;
  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String FOOD = "Food";
  private static final String MILK = "Milk";

  @Mock private AccountService accountService;
  @Mock private SettingsService settingsService;
  @Mock private SubdivisionService subdivisionService;
  @Mock private DeletionService deletionService;

  private CategoryService categoryService;

  @BeforeEach
  void setUp() {
    categoryService =
        new CategoryService(accountService, settingsService, subdivisionService, deletionService);
  }

  private static Account account(long id, String name, String type, Long parentId) {
    return new Account(id, name, type, parentId, EUR, null, null, null, null, false, false);
  }

  private static Account currencyLeaf(long id, String currencyCode, String type, long parentId) {
    return new Account(
        id, currencyCode, type, parentId, currencyCode, null, null, null, null, true, false);
  }

  @Test
  void topLevelCategoryIsBaseCurrencyLeaf() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    Account created = account(MILK_ID, FOOD, EXPENSE, null);
    when(accountService.insertLeaf(FOOD, EXPENSE, null, EUR)).thenReturn(created);

    Account result = categoryService.createCategory(new CategoryDraft(FOOD, EXPENSE, null));

    assertThat(result).isEqualTo(created);
  }

  @Test
  void childOfPostedLeafTriggersSubdivision() {
    Account parent = account(FOOD_ID, FOOD, EXPENSE, null);
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.of(parent));
    when(accountService.findChildrenOf(FOOD_ID)).thenReturn(List.of());
    when(accountService.hasPostings(FOOD_ID)).thenReturn(true);
    Account child = account(MILK_ID, MILK, EXPENSE, FOOD_ID);
    Account catchAll = account(UNCATEGORIZED_ID, "Uncategorized", EXPENSE, FOOD_ID);
    when(subdivisionService.subdivideAccount(FOOD_ID, MILK, "Uncategorized"))
        .thenReturn(new SubdivisionResult(child, catchAll));

    Account result = categoryService.createCategory(new CategoryDraft(MILK, EXPENSE, FOOD_ID));

    assertThat(result).isEqualTo(child);
    verify(accountService, never()).insertLeaf(any(), any(), any(), any());
  }

  @Test
  void childOfLeafWithOnlyCurrencyLeavesTriggersSubdivision() {
    // The bug scenario: "Food" was never posted to directly — its EUR/CHF spends already routed
    // onto auto-managed currency leaves — so adding a real child ("Restaurants") must still
    // subdivide, grouping the existing currency leaves under a new Uncategorized (plan 7d.1
    // follow-up), not just insert a plain sibling.
    Account parent = account(FOOD_ID, FOOD, EXPENSE, null);
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.of(parent));
    when(accountService.findChildrenOf(FOOD_ID))
        .thenReturn(
            List.of(
                currencyLeaf(CURRENCY_LEAF_EUR_ID, EUR, EXPENSE, FOOD_ID),
                currencyLeaf(CURRENCY_LEAF_CHF_ID, CHF, EXPENSE, FOOD_ID)));
    String restaurants = "Restaurants";
    Account child = account(MILK_ID, restaurants, EXPENSE, FOOD_ID);
    Account catchAll = account(UNCATEGORIZED_ID, "Uncategorized", EXPENSE, FOOD_ID);
    when(subdivisionService.subdivideAccount(FOOD_ID, restaurants, "Uncategorized"))
        .thenReturn(new SubdivisionResult(child, catchAll));

    Account result =
        categoryService.createCategory(new CategoryDraft(restaurants, EXPENSE, FOOD_ID));

    assertThat(result).isEqualTo(child);
    verify(accountService, never()).hasPostings(FOOD_ID);
    verify(accountService, never()).insertLeaf(any(), any(), any(), any());
  }

  @Test
  void childOfChildlessUnpostedLeafIsPlainInsert() {
    Account parent = account(FOOD_ID, FOOD, EXPENSE, null);
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.of(parent));
    when(accountService.findChildrenOf(FOOD_ID)).thenReturn(List.of());
    when(accountService.hasPostings(FOOD_ID)).thenReturn(false);
    Account child = account(MILK_ID, MILK, EXPENSE, FOOD_ID);
    when(accountService.insertLeaf(MILK, EXPENSE, FOOD_ID, EUR)).thenReturn(child);

    Account result = categoryService.createCategory(new CategoryDraft(MILK, EXPENSE, FOOD_ID));

    assertThat(result).isEqualTo(child);
    verify(subdivisionService, never()).subdivideAccount(any(Long.class), any(), any());
  }

  @Test
  void childOfAlreadySubdividedParentIsPlainInsert() {
    Account parent = account(FOOD_ID, FOOD, EXPENSE, null);
    when(accountService.findById(FOOD_ID)).thenReturn(Optional.of(parent));
    when(accountService.findChildrenOf(FOOD_ID))
        .thenReturn(List.of(account(UNCATEGORIZED_ID, "Uncategorized", EXPENSE, FOOD_ID)));
    Account child = account(MILK_ID, MILK, EXPENSE, FOOD_ID);
    when(accountService.insertLeaf(MILK, EXPENSE, FOOD_ID, EUR)).thenReturn(child);

    Account result = categoryService.createCategory(new CategoryDraft(MILK, EXPENSE, FOOD_ID));

    assertThat(result).isEqualTo(child);
    verify(accountService, never()).hasPostings(FOOD_ID);
    verify(subdivisionService, never()).subdivideAccount(any(Long.class), any(), any());
  }

  @Test
  void rejectsBlankNameBeforeAnyInsert() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> categoryService.createCategory(new CategoryDraft("  ", EXPENSE, null)))
        .withMessageContaining("name");

    verify(accountService, never()).insertLeaf(any(), any(), any(), any());
  }

  @Test
  void rejectsNameBeginningWithReservedSigil() {
    // A category named "for Kids" would be read as the for-sigil naming a person (data-model §7,
    // plan stage 8b.1). Accepted cost: it must be written "Kids".
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> categoryService.createCategory(new CategoryDraft("for Kids", EXPENSE, null)))
        .withMessageContaining("cannot begin with");

    verify(accountService, never()).insertLeaf(any(), any(), any(), any());
  }

  @Test
  void rejectsUnmanagedType() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> categoryService.createCategory(new CategoryDraft(FOOD, "asset", null)))
        .withMessageContaining("type");

    verify(accountService, never()).insertLeaf(any(), any(), any(), any());
  }

  @Test
  void rejectsParentOfDifferentType() {
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, FOOD, EXPENSE, null)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> categoryService.createCategory(new CategoryDraft("Bonus", INCOME, FOOD_ID)))
        .withMessageContaining(EXPENSE);

    verify(accountService, never()).insertLeaf(any(), any(), any(), any());
  }

  @Test
  void renameRejectsBlankName() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> categoryService.renameCategory(FOOD_ID, " "))
        .withMessageContaining("name");

    verify(accountService, never()).renameAccount(any(Long.class), any());
  }

  @Test
  void renameRefusesAccountsThisScreenDoesNotManage() {
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Giro", "asset", null)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> categoryService.renameCategory(FOOD_ID, "Girokonto"))
        .withMessageContaining("No manageable category");

    verify(accountService, never()).renameAccount(any(Long.class), any());
  }

  @Test
  void renameDelegatesToAccountService() {
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, FOOD, EXPENSE, null)));

    categoryService.renameCategory(FOOD_ID, "Groceries");

    verify(accountService).renameAccount(FOOD_ID, "Groceries");
  }

  @Test
  void deleteDelegatesToDeletionServiceForManageableCategory() {
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, FOOD, EXPENSE, null)));

    categoryService.deleteCategory(FOOD_ID, MILK_ID);

    verify(deletionService).deleteCategory(FOOD_ID, MILK_ID);
  }

  @Test
  void deleteRefusesAccountsThisScreenDoesNotManage() {
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, "Giro", "asset", null)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> categoryService.deleteCategory(FOOD_ID, MILK_ID))
        .withMessageContaining("No manageable category");

    verify(deletionService, never()).deleteCategory(anyLong(), anyLong());
  }

  // ── currency-leaf visibility (data-model §6.5, plan stage 7d.1 follow-up) ────

  @Test
  void manageableCategoriesExcludesCurrencyLeaves() {
    when(accountService.findLiveByTypesWithDepth(List.of(INCOME, EXPENSE)))
        .thenReturn(
            List.of(
                node(FOOD_ID, FOOD, EXPENSE, null),
                nodeOf(currencyLeaf(CURRENCY_LEAF_EUR_ID, EUR, EXPENSE, FOOD_ID))));

    assertThat(categoryService.manageableCategories())
        .extracting(n -> n.account().accountId())
        .containsExactly(FOOD_ID);
  }

  @Test
  void parentOptionsExcludesCurrencyLeaves() {
    when(accountService.findLiveByTypesWithDepth(List.of(INCOME, EXPENSE)))
        .thenReturn(
            List.of(
                node(FOOD_ID, FOOD, EXPENSE, null),
                nodeOf(currencyLeaf(CURRENCY_LEAF_EUR_ID, EUR, EXPENSE, FOOD_ID))));

    assertThat(categoryService.parentOptions(EXPENSE))
        .extracting(n -> n.account().accountId())
        .containsExactly(FOOD_ID);
  }

  @Test
  void deleteTargetOptionsNeverOffersCurrencyLeafItself() {
    when(accountService.findById(MILK_ID))
        .thenReturn(Optional.of(account(MILK_ID, MILK, EXPENSE, null)));
    when(accountService.findSubtreeAccountIds(MILK_ID)).thenReturn(List.of(MILK_ID));
    when(accountService.findLiveByTypesWithDepth(List.of(INCOME, EXPENSE)))
        .thenReturn(
            List.of(
                node(FOOD_ID, FOOD, EXPENSE, null),
                nodeOf(currencyLeaf(CURRENCY_LEAF_EUR_ID, EUR, EXPENSE, FOOD_ID))));
    when(accountService.findChildrenOf(FOOD_ID))
        .thenReturn(List.of(currencyLeaf(CURRENCY_LEAF_EUR_ID, EUR, EXPENSE, FOOD_ID)));

    List<Long> offeredIds =
        categoryService.deleteTargetOptions(MILK_ID).stream()
            .map(n -> n.account().accountId())
            .toList();

    // The currency leaf never shows up under its own id — only ever reached through its parent.
    assertThat(offeredIds).doesNotContain(CURRENCY_LEAF_EUR_ID);
  }

  @Test
  void deleteTargetOptionsExcludesCategoryThatStillHasCurrencyLeafChildren() {
    // "Fuel" has real postings, filed on its hidden EUR currency leaf — it is not a safe direct
    // target even though the leaf itself is never offered (plan stage 7d.1 follow-up): reassigning
    // postings straight onto "Fuel" would violate leaves-only, data-model §5.
    when(accountService.findById(MILK_ID))
        .thenReturn(Optional.of(account(MILK_ID, MILK, EXPENSE, null)));
    when(accountService.findSubtreeAccountIds(MILK_ID)).thenReturn(List.of(MILK_ID));
    when(accountService.findLiveByTypesWithDepth(List.of(INCOME, EXPENSE)))
        .thenReturn(
            List.of(
                node(FOOD_ID, FOOD, EXPENSE, null),
                node(FUEL_ID, "Fuel", EXPENSE, null),
                nodeOf(currencyLeaf(CURRENCY_LEAF_EUR_ID, EUR, EXPENSE, FUEL_ID))));
    when(accountService.findChildrenOf(FOOD_ID)).thenReturn(List.of());
    when(accountService.findChildrenOf(FUEL_ID))
        .thenReturn(List.of(currencyLeaf(CURRENCY_LEAF_EUR_ID, EUR, EXPENSE, FUEL_ID)));

    // Food (a genuine, childless leaf) is offered; Fuel (only currency-leaf children) is not.
    assertThat(categoryService.deleteTargetOptions(MILK_ID))
        .extracting(n -> n.account().accountId())
        .containsExactly(FOOD_ID);
  }

  // ── resolveCategory (the dock's category field, register §3.5) ───────────────

  private static AccountNode node(long id, String name, String type, Long parentId) {
    return new AccountNode(account(id, name, type, parentId), parentId == null ? 0 : 1);
  }

  private static AccountNode nodeOf(Account account) {
    return new AccountNode(account, 1);
  }

  @Test
  void resolveMatchesAnExistingCategoryByName() {
    when(accountService.findLiveByTypesWithDepth(List.of(INCOME, EXPENSE)))
        .thenReturn(List.of(node(FOOD_ID, FOOD, EXPENSE, null)));

    // Case-insensitive match to the existing category — no create.
    assertThat(categoryService.resolveCategory("food")).isEqualTo(FOOD_ID);
    verify(accountService, never()).insertLeaf(any(), any(), any(), any());
  }

  @Test
  void resolveCreatesChildUnderAnExistingParentFromParentChildText() {
    // "Food - Milk": Food resolves to the existing parent, Milk is the new child (type inherited).
    when(accountService.findLiveByTypesWithDepth(List.of(INCOME, EXPENSE)))
        .thenReturn(List.of(node(FOOD_ID, FOOD, EXPENSE, null)));
    when(accountService.findById(FOOD_ID))
        .thenReturn(Optional.of(account(FOOD_ID, FOOD, EXPENSE, null)));
    when(accountService.findChildrenOf(FOOD_ID)).thenReturn(List.of());
    when(accountService.hasPostings(FOOD_ID)).thenReturn(false);
    when(accountService.insertLeaf(MILK, EXPENSE, FOOD_ID, EUR))
        .thenReturn(account(MILK_ID, MILK, EXPENSE, FOOD_ID));

    assertThat(categoryService.resolveCategory("Food - Milk")).isEqualTo(MILK_ID);
  }

  @Test
  void resolveRejectsUnknownBareNameThatIsNotParentChild() {
    when(accountService.findLiveByTypesWithDepth(List.of(INCOME, EXPENSE))).thenReturn(List.of());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> categoryService.resolveCategory("Nonexistent"))
        .withMessageContaining("Parent - Child");
  }

  @Test
  void resolveRejectsParentChildWithUnknownParent() {
    when(accountService.findLiveByTypesWithDepth(List.of(INCOME, EXPENSE))).thenReturn(List.of());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> categoryService.resolveCategory("Ghost - Milk"))
        .withMessageContaining("No existing category");
  }

  @Test
  void resolveRejectsAnAmbiguousName() {
    // A same-named income and expense category — the dock cannot guess which was meant.
    when(accountService.findLiveByTypesWithDepth(List.of(INCOME, EXPENSE)))
        .thenReturn(
            List.of(node(FOOD_ID, "Bonus", INCOME, null), node(MILK_ID, "Bonus", EXPENSE, null)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> categoryService.resolveCategory("Bonus"))
        .withMessageContaining("ambiguous");
  }
}
