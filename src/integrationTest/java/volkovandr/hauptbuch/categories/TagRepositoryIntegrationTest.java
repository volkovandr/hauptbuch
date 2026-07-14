package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.categories.repository.TagRepository;

/**
 * Integration tier (plan §1.5): {@link TagRepository} maps {@code tag} rows ↔ {@link Tag} records
 * against real Postgres, and its reuse lookup ({@link TagRepository#findByNameAndParent}) matches
 * case-insensitively with null-safe parent handling (data-model §10.1). Plain round-trips — the
 * hierarchy walk itself is unit-tested in {@code TagServiceTest}.
 *
 * <p>{@code @Transactional} rolls each test back so the reused container stays clean.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TagRepositoryIntegrationTest {

  @Autowired TagRepository tagRepository;

  @Test
  void insertsAndFindsTopLevelTag() {
    long carId = tagRepository.insert("Car", null);

    Tag loaded = tagRepository.findById(carId).orElseThrow();
    assertThat(loaded.tagId()).isEqualTo(carId);
    assertThat(loaded.name()).isEqualTo("Car");
    assertThat(loaded.parentId()).isNull();
    assertThat(loaded.deletedAt()).isNull();
  }

  @Test
  void insertsAndFindsChildTagUnderItsParent() {
    long carId = tagRepository.insert("Car", null);
    long passatId = tagRepository.insert("Passat", carId);

    Tag loaded = tagRepository.findById(passatId).orElseThrow();
    assertThat(loaded.name()).isEqualTo("Passat");
    assertThat(loaded.parentId()).isEqualTo(carId);
  }

  @Test
  void findsByNameCaseInsensitivelyRespectingTheParent() {
    long carId = tagRepository.insert("Car", null);
    long passatId = tagRepository.insert("Passat", carId);

    // Same name, different parent scope: "Passat" at top level is a distinct row and must not match
    // the one under Car.
    long topLevelPassatId = tagRepository.insert("Passat", null);

    assertThat(tagRepository.findByNameAndParent("car", null).map(Tag::tagId)).contains(carId);
    assertThat(tagRepository.findByNameAndParent("PASSAT", carId).map(Tag::tagId))
        .contains(passatId);
    assertThat(tagRepository.findByNameAndParent("passat", null).map(Tag::tagId))
        .contains(topLevelPassatId);
  }

  @Test
  void findsNothingForAnUnknownName() {
    assertThat(tagRepository.findByNameAndParent("Nonexistent", null)).isEmpty();
    assertThat(tagRepository.findById(-1L)).isEqualTo(Optional.empty());
  }
}
