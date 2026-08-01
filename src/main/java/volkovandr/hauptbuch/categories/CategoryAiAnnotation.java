package volkovandr.hauptbuch.categories;

/**
 * The AI-vocabulary annotation for one row of the categories list (plan stage 9d): what the list
 * shows <em>only</em> where the operator has curated something, so the eye lands on the deviations
 * rather than a wall of "default" markers. A row earns an annotation when its effective visibility
 * differs from the type default, or it carries an alias, or it carries a note.
 *
 * @param visible the effective visibility (only meaningful to show when {@code deviates})
 * @param deviates whether the effective visibility differs from the type default (expense visible,
 *     income hidden) — the trigger for the "AI: hidden/visible" chip
 * @param setHere whether this row's own flag drives the visibility (solid <em>(set here)</em>) as
 *     opposed to an inherited one (muted <em>(via ⟨ancestor⟩)</em>)
 * @param viaName the ancestor whose flag is inherited, when {@code deviates} and not {@code
 *     setHere}; {@code null} otherwise
 * @param alias the operator's alias (the AI-facing name), or {@code null} if none
 * @param note the operator's AI note, or {@code null} if none
 */
public record CategoryAiAnnotation(
    boolean visible, boolean deviates, boolean setHere, String viaName, String alias, String note) {

  /** Whether this annotation carries anything worth showing (deviation, alias, or note). */
  public boolean hasContent() {
    return deviates || alias != null || note != null;
  }
}
