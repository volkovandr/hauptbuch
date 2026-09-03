package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.categories.CategoryService;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryTagRepository;
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;
import volkovandr.hauptbuch.ledger.LedgerService;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportCategoryMapPanel} read-model assembly with its
 * collaborators mocked — resolved target paths, the per-path sign evidence and the type it
 * proposes, the attached-tag pills, and the category / tag option lists (import.md §5.2; plan d1).
 */
@ExtendWith(MockitoExtension.class)
class ImportCategoryMapPanelTest {

  private static final long SESSION_ID = 1L;

  @Mock ImportCategoryRepository importCategoryRepository;
  @Mock ImportCategoryTagRepository importCategoryTagRepository;
  @Mock ImportStatisticsRepository importStatisticsRepository;
  @Mock CategoryService categoryService;
  @Mock LedgerService ledgerService;

  private ImportCategoryMapPanel panel() {
    return new ImportCategoryMapPanel(
        importCategoryRepository,
        importCategoryTagRepository,
        importStatisticsRepository,
        categoryService,
        ledgerService);
  }

  private static ImportCategory row(long id, String path, Long accountId) {
    return new ImportCategory(id, SESSION_ID, path, accountId, 0, 0, null);
  }

  @Test
  void buildsRowsWithTargetPathSignEvidenceAndTagPills() {
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(row(10L, "Audi:Fuel", 42L), row(11L, "Salary", null)));
    when(importStatisticsRepository.perCategoryPath(SESSION_ID))
        .thenReturn(
            List.of(
                new ImportCategorySignEvidence("Audi:Fuel", 8, 1),
                new ImportCategorySignEvidence("Salary", 0, 12)));
    when(categoryService.postableCategoryPaths())
        .thenReturn(
            List.of(
                new AccountPath(42L, "Transportation - Car - Fuel"),
                new AccountPath(50L, "Income - Salary")));
    when(importCategoryTagRepository.tagIdsBySession(SESSION_ID))
        .thenReturn(Map.of(10L, List.of(9L)));
    when(ledgerService.labelsForTagIds(anyList())).thenReturn(Map.of(9L, "Cars:Audi"));
    when(ledgerService.liveTagLabels()).thenReturn(List.of("Cars:Audi", "Trips:Prague"));

    ImportCategoryMap result = panel().forSession(SESSION_ID);

    assertThat(result.rows())
        .satisfiesExactly(
            fuel -> {
              assertThat(fuel.moneyPath()).isEqualTo("Audi:Fuel");
              assertThat(fuel.mapped()).isTrue();
              assertThat(fuel.targetPath()).isEqualTo("Transportation - Car - Fuel");
              assertThat(fuel.debitLineCount()).isEqualTo(8);
              assertThat(fuel.creditLineCount()).isEqualTo(1);
              assertThat(fuel.proposedType()).isEqualTo("expense");
              assertThat(fuel.tags())
                  .singleElement()
                  .satisfies(
                      tag -> {
                        assertThat(tag.tagId()).isEqualTo(9L);
                        assertThat(tag.label()).isEqualTo("Cars:Audi");
                      });
            },
            salary -> {
              assertThat(salary.mapped()).isFalse();
              assertThat(salary.targetPath()).isNull();
              assertThat(salary.proposedType()).isEqualTo("income");
              assertThat(salary.tags()).isEmpty();
            });
    assertThat(result.categoryOptions())
        .extracting(ImportCategoryMap.CategoryOption::path)
        .containsExactly("Transportation - Car - Fuel", "Income - Salary");
    assertThat(result.tagOptions()).containsExactly("Cars:Audi", "Trips:Prague");
  }

  @Test
  void proposesTypeFromTheMajoritySignAndNullWhenThePathHasNoStagedLine() {
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(row(10L, "MostlyIncome", null), row(11L, "Orphan", null)));
    when(importStatisticsRepository.perCategoryPath(SESSION_ID))
        .thenReturn(List.of(new ImportCategorySignEvidence("MostlyIncome", 3, 4)));
    when(categoryService.postableCategoryPaths()).thenReturn(List.of());
    when(importCategoryTagRepository.tagIdsBySession(SESSION_ID)).thenReturn(Map.of());
    when(ledgerService.labelsForTagIds(anyList())).thenReturn(Map.of());
    when(ledgerService.liveTagLabels()).thenReturn(List.of());

    assertThat(panel().forSession(SESSION_ID).rows())
        .satisfiesExactly(
            income -> assertThat(income.proposedType()).isEqualTo("income"),
            orphan -> assertThat(orphan.proposedType()).isNull());
  }

  @Test
  void panelIsEmptyWhenNothingHasBeenStaged() {
    when(importCategoryRepository.findBySession(SESSION_ID)).thenReturn(List.of());
    when(importStatisticsRepository.perCategoryPath(SESSION_ID)).thenReturn(List.of());
    when(categoryService.postableCategoryPaths()).thenReturn(List.of());
    when(importCategoryTagRepository.tagIdsBySession(SESSION_ID)).thenReturn(Map.of());
    when(ledgerService.labelsForTagIds(anyList())).thenReturn(Map.of());
    when(ledgerService.liveTagLabels()).thenReturn(List.of());

    assertThat(panel().forSession(SESSION_ID).rows()).isEmpty();
  }
}
