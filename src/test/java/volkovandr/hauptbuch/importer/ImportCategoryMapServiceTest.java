package volkovandr.hauptbuch.importer;

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
import volkovandr.hauptbuch.categories.CategoryService;
import volkovandr.hauptbuch.categories.TagService;
import volkovandr.hauptbuch.categories.TagService.ResolvedChip;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryTagRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportCategoryMapService} resolution logic with every repository
 * and cross-module service mocked — mapping a Money path to a category (import.md §5.2; plan d1),
 * replacing a row's tag set, the bulk map / bulk tag, the currency-leaf / group guard, and the
 * open-session and row-membership guards.
 */
@ExtendWith(MockitoExtension.class)
class ImportCategoryMapServiceTest {

  private static final long SESSION_ID = 1L;

  @Mock ImportSessionService importSessionService;
  @Mock ImportCategoryRepository importCategoryRepository;
  @Mock ImportCategoryTagRepository importCategoryTagRepository;
  @Mock CategoryService categoryService;
  @Mock TagService tagService;

  private ImportCategoryMapService service() {
    return new ImportCategoryMapService(
        importSessionService,
        importCategoryRepository,
        importCategoryTagRepository,
        categoryService,
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
  void mapsPathToPostableCategory() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));
    when(categoryService.isPostableCategory(42L)).thenReturn(true);

    service().mapToCategory(10L, 42L);

    verify(importCategoryRepository).mapToCategory(10L, 42L);
  }

  @Test
  void rejectsMappingToGroupOrCurrencyLeaf() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));
    when(categoryService.isPostableCategory(7L)).thenReturn(false);

    assertThatThrownBy(() -> service().mapToCategory(10L, 7L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("category leaf");

    verify(importCategoryRepository, never()).mapToCategory(anyLong(), anyLong());
  }

  @Test
  void rejectsRowNotInTheOpenSession() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));

    assertThatThrownBy(() -> service().mapToCategory(999L, 42L))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(categoryService);
    verify(importCategoryRepository, never()).mapToCategory(anyLong(), anyLong());
  }

  @Test
  void mapWithoutAnOpenSessionIsRejected() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().mapToCategory(10L, 42L))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(importCategoryRepository, categoryService);
  }

  @Test
  void setTagsRewritesTheRowsJunctionToTheLiveIds() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));
    when(tagService.exists(5L)).thenReturn(true);
    when(tagService.exists(6L)).thenReturn(true);

    service().setTags(10L, List.of(5L, 6L, 5L));

    verify(importCategoryTagRepository).clearTags(10L);
    verify(importCategoryTagRepository).addTag(10L, 5L);
    verify(importCategoryTagRepository).addTag(10L, 6L);
  }

  @Test
  void setTagsDropsUnknownIdsAndAnEmptySetJustClears() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));
    when(tagService.exists(5L)).thenReturn(false);

    service().setTags(10L, List.of(5L));
    service().setTags(10L, List.of());

    verify(importCategoryTagRepository, never()).addTag(anyLong(), anyLong());
    verify(importCategoryTagRepository, org.mockito.Mockito.times(2)).clearTags(10L);
  }

  @Test
  void bulkMapValidatesEveryRowAndTheTargetOnce() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel"), unmapped(11L, "Audi:Repair")));
    when(categoryService.isPostableCategory(42L)).thenReturn(true);

    service().bulkMapToCategory(List.of(10L, 11L), 42L);

    verify(importCategoryRepository).mapToCategory(10L, 42L);
    verify(importCategoryRepository).mapToCategory(11L, 42L);
  }

  @Test
  void bulkMapRejectsIfAnyRowIsNotInTheOpenSession() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));

    assertThatThrownBy(() -> service().bulkMapToCategory(List.of(10L, 999L), 42L))
        .isInstanceOf(IllegalArgumentException.class);

    verify(importCategoryRepository, never()).mapToCategory(anyLong(), anyLong());
  }

  @Test
  void bulkMapWithNothingTickedIsRejected() {
    assertThatThrownBy(() -> service().bulkMapToCategory(List.of(), 42L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Tick at least one");

    verify(importCategoryRepository, never()).mapToCategory(anyLong(), anyLong());
  }

  @Test
  void bulkAddTagResolvesTheChipOnceAndAddsItToEveryRow() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel"), unmapped(11L, "Audi:Repair")));
    when(tagService.resolveChip("Cars:Audi"))
        .thenReturn(Optional.of(new ResolvedChip(9L, "Cars:Audi")));

    service().bulkAddTag(List.of(10L, 11L), "Cars:Audi");

    verify(importCategoryTagRepository).addTag(10L, 9L);
    verify(importCategoryTagRepository).addTag(11L, 9L);
  }

  @Test
  void bulkAddTagRejectsBlankChip() {
    openSession();
    when(importCategoryRepository.findBySession(SESSION_ID))
        .thenReturn(List.of(unmapped(10L, "Audi:Fuel")));
    when(tagService.resolveChip(" ")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().bulkAddTag(List.of(10L), " "))
        .isInstanceOf(IllegalArgumentException.class);

    verify(importCategoryTagRepository, never()).addTag(anyLong(), anyLong());
  }
}
