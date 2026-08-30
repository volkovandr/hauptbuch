package volkovandr.hauptbuch.categories;

/**
 * The outcome of resolving a typed category — the shape <em>every</em> category picker renders
 * from: the register's simple dock, the register split's lines, and the receipts post-process
 * editor's lines (they all post to the one {@code /categories/resolve} endpoint).
 *
 * <p>Deliberately three-way, mirroring {@code debts}' {@code PersonResolution}, because creating a
 * category from a typed {@code Parent - Child} must never be a silent side effect of leaving the
 * field: a typo used to become a category (receipt-processing/25, which absorbed
 * transaction-register-ui/14). An unrecognised-but-creatable name is <em>proposed</em> here and
 * created only once the user says so — the same "never silently" stance reviving a soft-deleted
 * person already takes (data-model §7).
 */
public sealed interface CategoryResolution {

  /**
   * Ready to commit: the leaf id the picker carries into the commit.
   *
   * @param categoryId the resolved (or, after an explicit decision, newly created) leaf's id
   * @param statusText the caption to show under the picker, or {@code null} for a plain match — the
   *     input already shows what was picked, so only a <em>creation</em> is worth announcing
   */
  record Resolved(long categoryId, String statusText) implements CategoryResolution {}

  /**
   * The text names no existing category but would create one under an existing parent, and the user
   * has not said so yet. Nothing is created; no id is filled in, so the line simply fails
   * validation at commit like any other unresolved category.
   *
   * @param childName the leaf that would be created
   * @param parentName the existing category it would be created under
   */
  record Pending(String childName, String parentName) implements CategoryResolution {}

  /** Refused with a user-facing message — an unknown, ambiguous, or non-leaf name. */
  record Refused(String message) implements CategoryResolution {}
}
