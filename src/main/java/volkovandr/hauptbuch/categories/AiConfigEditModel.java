package volkovandr.hauptbuch.categories;

/**
 * The AI-parsing section's state for one category on its edit page (plan stage 9d): the operator's
 * own overrides, plus what the "Inherit" choice currently resolves to so its radio can spell it out
 * ("Inherit — currently: visible (via parent 'Food')"). The whole point of the tri-state is that
 * inherit is not a blank — it has a concrete effective result and a source — and the editor shows
 * both.
 *
 * @param visible this category's own tri-state flag: {@code TRUE} visible, {@code FALSE} hidden,
 *     {@code null} = inherit (which radio is selected)
 * @param alias the operator's alias, or {@code null} if none
 * @param note the operator's AI note, or {@code null} if none
 * @param inheritedVisible what visibility "Inherit" resolves to right now (the nearest set
 *     ancestor, else the type default)
 * @param inheritedSource a human phrase naming where that inherited value comes from, e.g. {@code
 *     via parent 'Food'} or {@code the type default}
 */
public record AiConfigEditModel(
    Boolean visible, String alias, String note, boolean inheritedVisible, String inheritedSource) {}
