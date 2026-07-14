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
      } else {
        deepest = tagRepository.insert(name, parentId);
        canonicalPath.add(name);
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
