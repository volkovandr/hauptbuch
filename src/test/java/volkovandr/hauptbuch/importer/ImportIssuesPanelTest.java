package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.categories.CategoryService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportIssuesPanel} read-model assembly with its collaborators
 * mocked — the referenced-only account/category scoping (orphan rows excluded, plan e4), the stale
 * category re-check (a single {@code postableCategoryPaths()} fetch, not one query per row), and
 * the expect-file and unresolved-park-leg counts (the latter passed in, not queried here).
 */
@ExtendWith(MockitoExtension.class)
class ImportIssuesPanelTest {

  private static final long SESSION_ID = 1L;

  @Mock ImportAccountRepository importAccountRepository;
  @Mock ImportCategoryRepository importCategoryRepository;
  @Mock CategoryService categoryService;

  private ImportIssuesPanel panel() {
    return new ImportIssuesPanel(
        importAccountRepository, importCategoryRepository, categoryService);
  }

  private static ImportAccount account(long id, String name, Long accountId, boolean expectFile) {
    return new ImportAccount(id, SESSION_ID, name, accountId, null, null, expectFile, null, null);
  }

  private static ImportCategory category(long id, String path, Long accountId) {
    return new ImportCategory(id, SESSION_ID, path, accountId, 0, 0, null);
  }

  @Test
  void listsUnmappedAndExpectingFileAccountsAmongTheReferencedRows() {
    when(importAccountRepository.findReferencedBySession(SESSION_ID))
        .thenReturn(
            List.of(
                account(10L, "Cash", null, true),
                account(11L, "Franc", 20L, true),
                account(12L, "Giro", 21L, false)));
    when(importCategoryRepository.findReferencedBySession(SESSION_ID)).thenReturn(List.of());

    ImportIssues result = panel().forSession(SESSION_ID, 0);

    assertThat(result.unmappedAccounts())
        .containsExactly(new ImportIssues.UnmappedRow(10L, "Cash"));
    assertThat(result.expectingFile())
        .containsExactly(
            new ImportIssues.UnmappedRow(10L, "Cash"), new ImportIssues.UnmappedRow(11L, "Franc"));
    assertThat(result.locked()).isTrue();
    assertThat(result.lockReasons())
        .containsExactly("2 account(s) still expecting a file", "1 account(s) not yet mapped");
  }

  @Test
  void marksMappedCategoryStaleWhenTargetIsNoLongerPostableFetchingOptionsOnce() {
    when(importAccountRepository.findReferencedBySession(SESSION_ID)).thenReturn(List.of());
    when(importCategoryRepository.findReferencedBySession(SESSION_ID))
        .thenReturn(
            List.of(
                category(30L, "Audi:Fuel", 40L),
                category(31L, "Salary", null),
                category(32L, "Food", 41L)));
    // 40L is no longer among the postable leaves (a mid-campaign subdivision) — 41L still is.
    when(categoryService.postableCategoryPaths()).thenReturn(List.of(new AccountPath(41L, "Food")));

    ImportIssues result = panel().forSession(SESSION_ID, 0);

    assertThat(result.unmappedCategories())
        .containsExactlyInAnyOrder(
            new ImportIssues.CategoryRow(30L, "Audi:Fuel", true),
            new ImportIssues.CategoryRow(31L, "Salary", false));
    assertThat(result.locked()).isTrue();
    // One fetch for the whole session, not one per referenced category row.
    verify(categoryService).postableCategoryPaths();
    verify(categoryService, never()).isPostableCategory(anyLong());
  }

  @Test
  void takesTheUnresolvedParkLegCountFromItsCaller() {
    when(importAccountRepository.findReferencedBySession(SESSION_ID)).thenReturn(List.of());
    when(importCategoryRepository.findReferencedBySession(SESSION_ID)).thenReturn(List.of());

    ImportIssues result = panel().forSession(SESSION_ID, 2);

    assertThat(result.unresolvedParkLegCount()).isEqualTo(2);
    assertThat(result.locked()).isTrue();
    assertThat(result.lockReasons()).containsExactly("2 still-parked cross-currency leg(s)");
  }

  @Test
  void unlockedWhenNothingIsOutstanding() {
    when(importAccountRepository.findReferencedBySession(SESSION_ID))
        .thenReturn(List.of(account(10L, "Giro", 20L, false)));
    when(importCategoryRepository.findReferencedBySession(SESSION_ID))
        .thenReturn(List.of(category(30L, "Food", 40L)));
    when(categoryService.postableCategoryPaths()).thenReturn(List.of(new AccountPath(40L, "Food")));

    ImportIssues result = panel().forSession(SESSION_ID, 0);

    assertThat(result.locked()).isFalse();
    assertThat(result.lockReasons()).isEmpty();
    assertThat(result.empty()).isTrue();
  }
}
