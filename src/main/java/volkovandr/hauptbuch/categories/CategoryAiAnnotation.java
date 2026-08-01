package volkovandr.hauptbuch.categories;

/**
 * The AI-vocabulary annotation for one row of the categories list (plan stage 9d): what the list
 * shows <em>only</em> where the operator has curated something, so the eye lands on the deviations
 * rather than a wall of "default" markers. A row earns an annotation when its effective visibility
 * differs from the type default, or — <em>only while it is visible to the AI</em> — it carries an
 * alias or a note. A hidden category's alias/note never reach the parser, so they are not surfaced.
 *
 * @param visible the effective visibility — also gates whether the alias/note are shown
 * @param deviates whether the effective visibility differs from the type default (expense visible,
 *     income hidden) — the trigger for the "AI: hidden/visible" label
 * @param setHere whether this row's own flag drives the visibility (shown plainly) as opposed to an
 *     inherited one (which the label then names with "(via ⟨ancestor⟩)")
 * @param viaName the ancestor whose flag is inherited, when {@code deviates} and not {@code
 *     setHere}; {@code null} otherwise
 * @param alias the operator's alias (the AI-facing name), or {@code null} if none
 * @param note the operator's AI note, or {@code null} if none
 */
public record CategoryAiAnnotation(
    boolean visible, boolean deviates, boolean setHere, String viaName, String alias, String note) {

  /**
   * Whether this annotation carries anything worth showing: a visibility deviation always, but an
   * alias or note only while the category is visible to the AI (a hidden category's alias/note are
   * never sent, so they are not surfaced on the list).
   */
  public boolean hasContent() {
    return deviates || (visible && (alias != null || note != null));
  }
}
