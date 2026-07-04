package volkovandr.hauptbuch.accounts;

/**
 * An account annotated with its depth in the parent-chain hierarchy (0 = top level, 1 = child, 2 =
 * grandchild, …) — a display-only projection for screens that render the tree with indentation.
 * Depth is not a stored fact; it is walked from {@code parent_id} at read time (data-model §5's
 * hierarchy is arbitrarily deep, not just parent/child).
 *
 * @param account the account
 * @param depth its distance from the nearest top-level ancestor
 */
public record AccountNode(Account account, int depth) {}
