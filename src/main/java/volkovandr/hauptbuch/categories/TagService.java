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
   * Resolve one committed chip (register §3.6) to the tag it names and a canonical display label.
   * The chip is a {@code Parent:Child} path: every segment is reused if a live tag of that name
   * (case-insensitive) already exists under the running parent, else inserted — so re-typing {@code
   * car:passat} lands the same {@code Passat} under the same {@code Car} (shown canonically as
   * {@code Car:Passat}), while a genuinely new leaf creates just the missing levels. The
   * <strong>deepest</strong> segment's id is the one a posting is tagged with: {@code Car:Passat}
   * attaches {@code Passat} alone (the rollup later walks the subtree to fold it into a {@code Car}
   * report — data-model §10.3), while bare {@code Car} attaches {@code Car}.
   *
   * <p>Blank path segments (a stray {@code Car:}) and surrounding whitespace are tolerated; a chip
   * with no non-blank segment resolves to empty (the dock never commits one).
   *
   * @param chip the typed chip, a {@code Parent:Child} path
   * @return the resolved leaf tag id and its canonical label, or empty for a blank chip
   */
  @Transactional
  public Optional<ResolvedChip> resolveChip(String chip) {
    return walkSegments(chip, true);
  }

  /** Whether a live tag with this id exists — the importer's guard before it stores a chosen id. */
  public boolean exists(long tagId) {
    return tagRepository.findById(tagId).filter(tag -> tag.deletedAt() == null).isPresent();
  }

  /**
   * Resolve a tag echo from the AI parser to an <em>existing</em> tag, <strong>without creating
   * anything</strong> (data-model §13.3, plan stage 9d). The AI emits a tag only when a
   * per-category note instructs it, echoing the name the note itself supplied — a suggestion, never
   * a creation — so this is the non-creating sibling of {@link #resolveChip}: the full {@code
   * Parent:Child} path must already exist (each segment reused case-insensitively under the running
   * parent); any missing segment resolves to empty and the seeding silently drops the echo.
   *
   * @param chip the AI's echoed {@code Parent:Child} path
   * @return the existing leaf tag id and its canonical label, or empty if the path is blank or any
   *     segment does not exist
   */
  public Optional<ResolvedChip> resolveExistingChip(String chip) {
    return walkSegments(chip, false);
  }

  /**
   * Walk a {@code Parent:Child} chip segment by segment, reusing an existing tag of that name
   * (case-insensitive) under the running parent. When a segment does not exist, {@code create}
   * decides the difference between the two public methods: {@code true} inserts the missing level
   * (the entry dock's resolve-or-create), {@code false} abandons the whole chip as unresolved (the
   * AI echo's non-creating lookup). The deepest segment's id is the one a posting is tagged with;
   * the label is composed from the stored (canonical) spellings.
   */
  private Optional<ResolvedChip> walkSegments(String chip, boolean create) {
    if (chip == null || chip.isBlank()) {
      return Optional.empty();
    }
    Long parentId = null;
    Long deepest = null;
    List<String> canonicalPath = new ArrayList<>();
    for (String rawSegment : chip.split(HIERARCHY_SEPARATOR)) {
      String name = rawSegment.strip();
      if (name.isEmpty()) {
        continue;
      }
      Optional<Tag> existing = tagRepository.findByNameAndParent(name, parentId);
      if (existing.isPresent()) {
        deepest = existing.get().tagId();
        // Reuse the stored spelling so `car` displays as the canonical `Car`.
        canonicalPath.add(existing.get().name());
      } else if (create) {
        deepest = tagRepository.insert(name, parentId);
        canonicalPath.add(name);
      } else {
        return Optional.empty(); // a missing segment → no match, nothing created
      }
      parentId = deepest;
    }
    if (deepest == null) {
      return Optional.empty();
    }
    return Optional.of(new ResolvedChip(deepest, String.join(HIERARCHY_SEPARATOR, canonicalPath)));
  }

  /**
   * A resolved chip: the leaf tag id a posting is tagged with, and the canonical {@code
   * Parent:Child} label to show on the pill (register §3.6).
   *
   * @param tagId the deepest-segment tag id
   * @param label the canonical hierarchy label, segments joined by {@code :}
   */
  public record ResolvedChip(long tagId, String label) {}
}
