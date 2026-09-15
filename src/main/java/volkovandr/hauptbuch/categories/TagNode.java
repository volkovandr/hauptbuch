package volkovandr.hauptbuch.categories;

/**
 * A tag annotated with its depth in the parent-chain hierarchy (0 = top level, 1 = child, 2 =
 * grandchild, …) — a display-only projection for screens that render the tag tree with indentation,
 * mirroring {@link volkovandr.hauptbuch.accounts.AccountNode} for the account hierarchy. Depth is
 * not a stored fact; it is walked from {@code parent_id} at read time (data-model §10.1).
 *
 * @param tag the tag
 * @param depth its distance from the nearest top-level ancestor
 */
public record TagNode(Tag tag, int depth) {}
