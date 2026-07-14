package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.categories.TagService.ResolvedChip;
import volkovandr.hauptbuch.categories.repository.TagRepository;

/**
 * Unit tier (plan §1.5): {@link TagService#resolveChip}'s parse-and-reuse logic (register §3.6) —
 * the {@code Parent:Child} hierarchy walk, reuse of an existing level rather than a forked
 * duplicate, the canonical label it composes, and which id a chip contributes. Pure over the tag
 * repository, mocked; no container (CLAUDE.md §6).
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

    ResolvedChip chip = tagService.resolveChip("Groceries").orElseThrow();

    assertThat(chip.tagId()).isEqualTo(7L);
    assertThat(chip.label()).isEqualTo("Groceries");
    verify(tagRepository, never()).insert(eq("Groceries"), isNull());
  }

  @Test
  void createsNewTopLevelTagWhenNoneExists() {
    when(tagRepository.findByNameAndParent("Fuel", null)).thenReturn(Optional.empty());
    when(tagRepository.insert("Fuel", null)).thenReturn(42L);

    ResolvedChip chip = tagService.resolveChip("Fuel").orElseThrow();

    assertThat(chip.tagId()).isEqualTo(42L);
    assertThat(chip.label()).isEqualTo("Fuel");
  }

  @Test
  void resolvesHierarchyReusingParentAndCreatingOnlyTheMissingChild() {
    // Car exists; Passat under it does not — only the leaf is created, under the reused Car.
    when(tagRepository.findByNameAndParent("Car", null))
        .thenReturn(Optional.of(tag(3, "Car", null)));
    when(tagRepository.findByNameAndParent("Passat", 3L)).thenReturn(Optional.empty());
    when(tagRepository.insert("Passat", 3L)).thenReturn(9L);

    ResolvedChip chip = tagService.resolveChip("Car:Passat").orElseThrow();

    // Only the deepest segment (Passat) tags the posting — the rollup walks the subtree (§10.3).
    assertThat(chip.tagId()).isEqualTo(9L);
    assertThat(chip.label()).isEqualTo("Car:Passat");
    verify(tagRepository, never()).insert(eq("Car"), isNull());
  }

  @Test
  void createsBothLevelsOfWhollyNewPath() {
    when(tagRepository.findByNameAndParent("Trip", null)).thenReturn(Optional.empty());
    when(tagRepository.insert("Trip", null)).thenReturn(10L);
    when(tagRepository.findByNameAndParent("Prague", 10L)).thenReturn(Optional.empty());
    when(tagRepository.insert("Prague", 10L)).thenReturn(11L);

    ResolvedChip chip = tagService.resolveChip("Trip:Prague").orElseThrow();

    assertThat(chip.tagId()).isEqualTo(11L);
    assertThat(chip.label()).isEqualTo("Trip:Prague");
  }

  @Test
  void composesTheCanonicalLabelFromStoredSpellingNotTheTypedCase() {
    // The repository matches case-insensitively; the pill shows the stored canonical spelling.
    when(tagRepository.findByNameAndParent("car", null))
        .thenReturn(Optional.of(tag(3, "Car", null)));
    when(tagRepository.findByNameAndParent("passat", 3L))
        .thenReturn(Optional.of(tag(4, "Passat", 3L)));

    ResolvedChip chip = tagService.resolveChip(" car : passat ").orElseThrow();

    assertThat(chip.tagId()).isEqualTo(4L);
    assertThat(chip.label()).isEqualTo("Car:Passat");
  }

  @Test
  void toleratesStraySeparatorsLeavingJustTheNamedLevels() {
    when(tagRepository.findByNameAndParent("Car", null))
        .thenReturn(Optional.of(tag(3, "Car", null)));

    // A trailing separator ("Car:") leaves just Car.
    ResolvedChip chip = tagService.resolveChip("Car:").orElseThrow();

    assertThat(chip.tagId()).isEqualTo(3L);
    assertThat(chip.label()).isEqualTo("Car");
  }

  @Test
  void resolvesToEmptyForBlankOrSeparatorOnlyInput() {
    assertThat(tagService.resolveChip(null)).isEmpty();
    assertThat(tagService.resolveChip("   ")).isEmpty();
    assertThat(tagService.resolveChip(":")).isEmpty();
    verify(tagRepository, never()).insert(any(), any());
  }
}
