package volkovandr.hauptbuch.ledger;

/**
 * One of the five account pickers the register filter offers as a tab strip (issue
 * transaction-register-ui/22): {@code Last used · Open · Persons · Closed · All}. The active picker
 * is part of the applied {@link RegisterFilter} — it decides which accounts an empty {@code
 * accountIds} resolves to, and it is carried through the entry dock so a commit re-renders the same
 * tab.
 *
 * <ul>
 *   <li>{@link #LAST_USED} — open real accounts <em>and</em> person leaves with a posting inside
 *       the applied date range; the default, and the one a bare {@code /register} opens on.
 *   <li>{@link #OPEN} — open real accounts; person leaves excluded.
 *   <li>{@link #PERSONS} — live people's per-currency debt leaves, unsettled people first.
 *   <li>{@link #CLOSED} — closed accounts (viewable, never bookable).
 *   <li>{@link #ALL} — every live own account leaf: open, closed, and person leaves.
 * </ul>
 */
public enum RegisterPicker {
  LAST_USED("last-used"),
  OPEN("open"),
  PERSONS("persons"),
  CLOSED("closed"),
  ALL("all");

  /** The picker a filter carries when none was chosen (register §2.3). */
  public static final RegisterPicker DEFAULT = LAST_USED;

  private final String token;

  RegisterPicker(String token) {
    this.token = token;
  }

  /** The stable {@code ?picker=} query-string token for this picker. */
  public String param() {
    return token;
  }

  /**
   * The picker named by a {@code ?picker=} token, or {@link #DEFAULT} for a blank, null, or
   * unrecognised value — a stale bookmark falls back to the default view rather than erroring.
   */
  public static RegisterPicker fromParam(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    String trimmed = value.trim();
    for (RegisterPicker picker : values()) {
      if (picker.token.equalsIgnoreCase(trimmed)) {
        return picker;
      }
    }
    return DEFAULT;
  }
}
