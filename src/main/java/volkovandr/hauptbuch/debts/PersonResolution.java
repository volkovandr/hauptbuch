package volkovandr.hauptbuch.debts;

/**
 * The outcome of resolving a typed {@code for <person>} / {@code by <person>} sigil against the
 * roster (register §3.5, data-model §7, plan stage 8b.1) — the shape <em>both</em> pickers render
 * from, so the Restore/Create-new revival flow is written once.
 *
 * <p>Deliberately carries no ids: a person's per-currency leaf is auto-provisioned at
 * <em>commit</em> (data-model §7), never speculatively at typing time, so what a resolve produces
 * is the name and direction the commit will provision from — plus, when the name matched only a
 * soft-deleted person, the revival decision the user made.
 */
public sealed interface PersonResolution {

  /**
   * Ready to commit: the name and direction the commit provisions from, and a caption to show
   * beside the field.
   *
   * @param personName the person's name, stripped of the sigil
   * @param direction {@code FOR} or {@code BY} ({@link PersonTarget.Direction})
   * @param revive {@code true}/{@code false} once a soft-deleted-only match has been decided;
   *     {@code null} when there was nothing to decide (the ≥95% path)
   * @param statusText the caption — e.g. {@code "for Max (new person)"}
   */
  record Resolved(String personName, String direction, Boolean revive, String statusText)
      implements PersonResolution {}

  /**
   * The name matches <em>only</em> a soft-deleted person, and the user has not chosen yet. Reviving
   * is never silent (data-model §7), so the picker renders a Restore/Create-new pair that re-posts
   * with a decision; leaving it undecided simply fails validation at Add, like any unresolved
   * field.
   */
  record Pending(String personName) implements PersonResolution {}

  /** Refused with a user-facing message — an ambiguous name the resolver must not guess at. */
  record Refused(String message) implements PersonResolution {}
}
