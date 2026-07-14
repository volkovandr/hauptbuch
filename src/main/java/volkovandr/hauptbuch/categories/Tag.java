package volkovandr.hauptbuch.categories;

import java.time.OffsetDateTime;

/**
 * A tag — an orthogonal, overlapping label on postings (data-model §10). Distinct from a category
 * (which is an account, one per posting): tags do the per-car / per-trip slicing (`Car:Audi`,
 * `Trip:Prague`). A real entity, not a bare string, so it carries a stable identity the rules
 * engine and learned mappings key off, and merge/rename operate on ids (data-model §10.1).
 *
 * <p>The hierarchy is a self-reference via {@code parentId} ({@code Car} → {@code Car:Audi});
 * unlike accounts it is <strong>not</strong> leaves-only — a posting may be tagged with a parent
 * directly and/or its leaves (data-model §10.3). Owned by {@code categories} (the shared taxonomy,
 * per the module map); the posting linkage ({@code posting_tag}) lives with the posting in {@code
 * ledger}.
 *
 * @param tagId surrogate PK; null for a not-yet-persisted tag
 * @param name display name of this level ({@code Passat}, not the full {@code Car:Passat} path)
 * @param parentId the parent tag; null for a top-level tag
 * @param deletedAt soft-delete timestamp; null while live
 */
public record Tag(Long tagId, String name, Long parentId, OffsetDateTime deletedAt) {}
