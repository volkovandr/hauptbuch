package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.categories.repository.TagRepository;

/**
 * Unit tier (plan §1.5): {@link TagService#resolveChips}'s parse-and-reuse logic (register §3.6) —
 * the {@code Parent:Child} hierarchy walk, reuse of an existing level rather than a forked
 * duplicate, and which id a chip contributes. Pure over the tag repository, mocked; no container
 * (CLAUDE.md §6).
 */
@ExtendWith(MockitoExtension.class)
class TagServiceTest {

  @Mock private TagRepository tagRepository;
  @InjectMocks private TagService tagService;

  private static Tag tag(long id, String name, Long parentId) {
    return new Tag(id, name, parentId, null);
  }

  @Test
  void reusesAnExistingTopLevelTag() {
    when(tagRepository.findByNameAndParent("Groceries", null))
        .thenReturn(Optional.of(tag(7, "Groceries", null)));

    List<Long> ids = tagService.resolveChips(List.of("Groceries"));

    assertThat(ids).containsExactly(7L);
    verify(tagRepository, never()).insert(eq("Groceries"), isNull());
  }

  @Test
  void createsNewTopLevelTagWhenNoneExists() {
    when(tagRepository.findByNameAndParent("Fuel", null)).thenReturn(Optional.empty());
    when(tagRepository.insert("Fuel", null)).thenReturn(42L);

    List<Long> ids = tagService.resolveChips(List.of("Fuel"));

    assertThat(ids).containsExactly(42L);
  }

  @Test
  void resolvesHierarchyReusingParentAndCreatingOnlyTheMissingChild() {
    // Car exists; Passat under it does not — only the leaf is created, under the reused Car.
    when(tagRepository.findByNameAndParent("Car", null))
        .thenReturn(Optional.of(tag(3, "Car", null)));
    when(tagRepository.findByNameAndParent("Passat", 3L)).thenReturn(Optional.empty());
    when(tagRepository.insert("Passat", 3L)).thenReturn(9L);

    List<Long> ids = tagService.resolveChips(List.of("Car:Passat"));

    // Only the deepest segment (Passat) tags the posting — the rollup walks the subtree (§10.3).
    assertThat(ids).containsExactly(9L);
    verify(tagRepository, never()).insert(eq("Car"), isNull());
  }

  @Test
  void createsBothLevelsOfWhollyNewPath() {
    when(tagRepository.findByNameAndParent("Trip", null)).thenReturn(Optional.empty());
    when(tagRepository.insert("Trip", null)).thenReturn(10L);
    when(tagRepository.findByNameAndParent("Prague", 10L)).thenReturn(Optional.empty());
    when(tagRepository.insert("Prague", 10L)).thenReturn(11L);

    List<Long> ids = tagService.resolveChips(List.of("Trip:Prague"));

    assertThat(ids).containsExactly(11L);
  }

  @Test
  void keepsSeveralChipsInOrderAndDropsDuplicates() {
    // The repository matches case-insensitively (lower(name) in SQL), so both "Car" and "car"
    // resolve to the same row — the mock reflects that.
    when(tagRepository.findByNameAndParent("Car", null))
        .thenReturn(Optional.of(tag(3, "Car", null)));
    when(tagRepository.findByNameAndParent("car", null))
        .thenReturn(Optional.of(tag(3, "Car", null)));
    when(tagRepository.findByNameAndParent("Trip", null))
        .thenReturn(Optional.of(tag(5, "Trip", null)));

    List<Long> ids = tagService.resolveChips(List.of("Car", "Trip", "car"));

    // "car" reuses the same id as "Car" → collapsed to one, order preserved.
    assertThat(ids).containsExactly(3L, 5L);
  }

  @Test
  void toleratesBlankChipsAndStraySeparators() {
    when(tagRepository.findByNameAndParent("Car", null))
        .thenReturn(Optional.of(tag(3, "Car", null)));
    when(tagRepository.findByNameAndParent("Passat", 3L))
        .thenReturn(Optional.of(tag(4, "Passat", 3L)));

    // A blank chip is dropped; a trailing separator ("Car:") leaves just Car; whitespace is
    // stripped around each segment.
    List<Long> ids = tagService.resolveChips(List.of("  ", " Car : Passat ", "Car:"));

    assertThat(ids).containsExactly(4L, 3L);
  }

  @Test
  void returnsNoTagsForNullOrEmptyInput() {
    assertThat(tagService.resolveChips(null)).isEmpty();
    assertThat(tagService.resolveChips(List.of())).isEmpty();
    verify(tagRepository, never()).insert(any(), any());
  }
}
