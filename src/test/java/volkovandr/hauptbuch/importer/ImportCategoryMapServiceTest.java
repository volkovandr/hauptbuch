package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.categories.CategoryResolution;
import volkovandr.hauptbuch.categories.CategoryResolutionService;
import volkovandr.hauptbuch.categories.CategoryService;
import volkovandr.hauptbuch.categories.TagService;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryTagRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportCategoryMapService} with every repository and cross-module
 * service mocked — mapping a Money path to a resolved category id and replacing its tags in one
 * write (import.md §5.2, §8; plan d1), the bulk form of the same, the postable-leaf guard, and the
 * open-session / row-membership / empty-selection guards. Resolving a typed new category is a thin
 * pass-through to {@code categories}' resolver, tested there.
 */
@ExtendWith(MockitoExtension.class)
class ImportCategoryMapServiceTest {

  private static final long SESSION_ID = 1L;

  @Mock ImportSessionService importSessionService;
  @Mock ImportCategoryRepository importCategoryRepository;
  @Mock ImportCategoryTagRepository importCategoryTagRepository;
  @Mock CategoryService categoryService;
  @Mock CategoryResolutionService categoryResolutionService;
  @Mock TagService tagService;

  private ImportCategoryMapService service() {
    return new ImportCategoryMapService(
        importSessionService,
        importCategoryRepository,
        importCategoryTagRepository,
        categoryService,
        categoryResolutionService,
        tagService);
  }

  private void openSession() {
    when(importSessionService.currentSession())
        .thenReturn(
            Optional.of(
                new ImportSession(
                    SESSION_ID, ImportSessionState.OPEN, null, null, OffsetDateTime.now(), null)));
  }

  private static ImportCategory unmapped(long id, String path) {
    return new ImportCategory(id, SESSION_ID, path, null, 0, 0, null);
  }

  @Test
  void mapsPathToCategoryAndReplacesItsTagsInOneWrite() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));
    when(categoryService.isPostableCategory(42L)).thenReturn(true);
    when(tagService.exists(5L)).thenReturn(true);
    when(tagService.exists(6L)).thenReturn(true);

    service().mapResolved(10L, 42L, List.of(5L, 6L, 5L));

    verify(importCategoryRepository).mapToCategory(10L, 42L);
    verify(importCategoryTagRepository).clearTags(10L);
    verify(importCategoryTagRepository).addTag(10L, 5L);
    verify(importCategoryTagRepository).addTag(10L, 6L);
  }

  @Test
  void mappingWithNoTagsJustClearsTheRowsTags() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));
    when(categoryService.isPostableCategory(42L)).thenReturn(true);

    service().mapResolved(10L, 42L, List.of());

    verify(importCategoryRepository).mapToCategory(10L, 42L);
    verify(importCategoryTagRepository).clearTags(10L);
    verify(importCategoryTagRepository, never()).addTag(anyLong(), anyLong());
  }

  @Test
  void rejectsMappingToGroupOrCurrencyLeaf() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));
    when(categoryService.isPostableCategory(7L)).thenReturn(false);

    assertThatThrownBy(() -> service().mapResolved(10L, 7L, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("category leaf");

    verify(importCategoryRepository, never()).mapToCategory(anyLong(), anyLong());
  }

  @Test
  void rejectsRowNotInTheOpenSession() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));

    assertThatThrownBy(() -> service().mapResolved(999L, 42L, List.of()))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(categoryService);
    verify(importCategoryRepository, never()).mapToCategory(anyLong(), anyLong());
  }

  @Test
  void mapWithoutAnOpenSessionIsRejected() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().mapResolved(10L, 42L, List.of()))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(importCategoryRepository, categoryService);
  }

  @Test
  void resolveNewCategoryDelegatesToTheResolverAskingItToCreate() {
    when(categoryResolutionService.resolveCategory(
            "Transportation - Car - Fuel", CategoryResolutionService.DECISION_CREATE))
        .thenReturn(new CategoryResolution.Resolved(99L, "new category: Fuel"));

    CategoryResolution result = service().resolveNewCategory("Transportation - Car - Fuel");

    assertThat(result).isEqualTo(new CategoryResolution.Resolved(99L, "new category: Fuel"));
  }

  @Test
  void bulkMapValidatesEveryRowAndTheTargetOnceThenWritesEach() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel"), unmapped(11L, "Audi:Repair")));
    when(categoryService.isPostableCategory(42L)).thenReturn(true);
    when(tagService.exists(9L)).thenReturn(true);

    service().bulkMapResolved(List.of(10L, 11L), 42L, List.of(9L));

    verify(importCategoryRepository).mapToCategory(10L, 42L);
    verify(importCategoryRepository).mapToCategory(11L, 42L);
    verify(importCategoryTagRepository).addTag(10L, 9L);
    verify(importCategoryTagRepository).addTag(11L, 9L);
    verify(categoryService, org.mockito.Mockito.times(1)).isPostableCategory(42L);
  }

  @Test
  void bulkMapRejectsIfAnyRowIsNotInTheOpenSession() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));

    assertThatThrownBy(() -> service().bulkMapResolved(List.of(10L, 999L), 42L, List.of()))
        .isInstanceOf(IllegalArgumentException.class);

    verify(importCategoryRepository, never()).mapToCategory(anyLong(), anyLong());
  }

  @Test
  void bulkMapWithNothingTickedIsRejected() {
    assertThatThrownBy(() -> service().bulkMapResolved(List.of(), 42L, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Tick at least one");

    verify(importCategoryRepository, never()).mapToCategory(anyLong(), anyLong());
  }

  @Test
  void requireMappableRowThrowsForStaleRowSoControllerChecksBeforeCategoryCreation() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));

    service().requireMappableRow(10L); // in the campaign — no throw
    assertThatThrownBy(() -> service().requireMappableRow(999L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requireMappableSelectionThrowsForNothingTickedOrAnUnknownId() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));

    service().requireMappableSelection(List.of(10L)); // valid — no throw
    assertThatThrownBy(() -> service().requireMappableSelection(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Tick at least one");
    assertThatThrownBy(() -> service().requireMappableSelection(List.of(10L, 999L)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
