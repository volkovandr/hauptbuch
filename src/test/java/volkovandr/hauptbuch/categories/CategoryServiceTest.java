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
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.DeletionService;
import volkovandr.hauptbuch.operations.SubdivisionResult;
import volkovandr.hauptbuch.operations.SubdivisionService;

/**
 * Unit tier (plan §1.5): category creation's decision logic with the DB mocked away — a top-level
 * category is a base-currency leaf, a child of a posted leaf triggers subdivision, a child of a
 * childless leaf or an already-subdivided parent is a plain insert, and the type/parent-type
 * validation rejects bad input before any write.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

  private static final long FOOD_ID = 1L;
  private static final long MILK_ID = 2L;
  private static final long UNCATEGORIZED_ID = 3L;
  private static final String EXPENSE = "expense";
  private static final String INCOME = "income";
  private static final String EUR = "EUR";
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
    return new Account(id, name, type, parentId, EUR, null, null, null, null);
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
}
