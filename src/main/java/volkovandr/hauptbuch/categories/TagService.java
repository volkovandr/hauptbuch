package volkovandr.hauptbuch.categories;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.categories.repository.TagRepository;

/**
 * Tag vocabulary reads and resolve-or-create for the entry dock's chip field (register §3.6, plan
 * stage 7e). A tag is shared-taxonomy reference data, so this is thin CRUD-with-parsing over {@link
 * TagRepository}, not an invariant-upholding domain operation (CLAUDE.md §1.7) — which is why it
 * lives in {@code categories} (the tag's owning module).
 *
 * <p>Owned here, not in {@code operations}: creating a tag is this module's logic, and {@code
 * operations → categories} would close a module cycle ({@code categories → operations} already
 * exists — the same reason category create-new lives here, plan stage 7 boundary note). So the dock
 * resolves its chips through the {@code categories} screen ({@code /categories/tags/resolve}) and
 * commits to {@code operations} with the returned ids — {@code operations} attaches those opaque
 * ids to the postings ({@code posting_tag} lives with the posting in {@code ledger}).
 *
 * <p>The one piece of real logic is {@link #resolveChips}: parsing a {@code Parent:Child} chip into
 * its hierarchy and reusing an existing tag rather than forking a duplicate. It is unit-tested with
 * the repository mocked.
 */
@Service
public class TagService {

  /** The chip-field hierarchy separator (register §3.6): {@code Car:Passat}. Not a regex meta. */
  private static final String HIERARCHY_SEPARATOR = ":";

  private final TagRepository tagRepository;

  TagService(TagRepository tagRepository) {
    this.tagRepository = tagRepository;
  }

  /**
   * Resolve the dock's committed chips to the tag ids a posting is tagged with (register §3.6).
   * Each chip is a {@code Parent:Child} path: every segment is reused if a live tag of that name
   * (case-insensitive) already exists under the running parent, else inserted — so re-typing {@code
   * Car:Passat} lands the same {@code Passat} under the same {@code Car}, and a genuinely new leaf
   * creates just the missing levels. Only the <strong>deepest</strong> segment's id is returned per
   * chip: tagging {@code Car:Passat} attaches {@code Passat} alone (the rollup query later walks
   * the subtree to fold it into a {@code Car} report — data-model §10.3), while tagging bare {@code
   * Car} attaches {@code Car} directly.
   *
   * <p>Blank chips (and blank path segments, e.g. a stray {@code Car:}) are tolerated and dropped;
   * duplicate ids are collapsed so a posting never carries the same tag twice (also the {@code
   * posting_tag} unique constraint). Order is preserved.
   *
   * @param chips the chip texts the dock committed, in entry order; null/empty yields no tags
   * @return the distinct deepest-segment tag ids, in first-seen order
   */
  @Transactional
  public List<Long> resolveChips(List<String> chips) {
    if (chips == null || chips.isEmpty()) {
      return List.of();
    }
    List<Long> ids = new ArrayList<>();
    for (String chip : chips) {
      Long id = resolveChip(chip);
      if (id != null && !ids.contains(id)) {
        ids.add(id);
      }
    }
    return List.copyOf(ids);
  }

  /**
   * Resolve one {@code Parent:Child} chip to its deepest segment's tag id, reusing or creating each
   * level from the top down. Returns {@code null} for a chip with no non-blank segment.
   */
  private Long resolveChip(String chip) {
    if (chip == null || chip.isBlank()) {
      return null;
    }
    Long parentId = null;
    Long deepest = null;
    for (String rawSegment : chip.split(HIERARCHY_SEPARATOR)) {
      String name = rawSegment.strip();
      if (name.isEmpty()) {
        continue;
      }
      Optional<Tag> existing = tagRepository.findByNameAndParent(name, parentId);
      long resolved = existing.map(Tag::tagId).orElseGet(insertUnder(name, parentId));
      deepest = resolved;
      parentId = resolved;
    }
    return deepest;
  }

  /**
   * A supplier that inserts {@code name} under {@code parentId} — extracted so {@link #resolveChip}
   * can use {@code Optional.orElseGet} without capturing the loop-mutated {@code parentId}
   * (effectively-final rule).
   */
  private java.util.function.Supplier<Long> insertUnder(String name, Long parentId) {
    return () -> tagRepository.insert(name, parentId);
  }
}
