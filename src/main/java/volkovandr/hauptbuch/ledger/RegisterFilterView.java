package volkovandr.hauptbuch.ledger;

import java.util.List;

/**
 * The register's account-filter control as a view model (issue transaction-register-ui/22): the
 * one-line tab strip plus the currently-active picker's panel. Assembled by {@link
 * RegisterPickerService} — the template only iterates.
 *
 * <p>The panel is a flat list of {@link Row}s so an arbitrarily deep account hierarchy and the
 * per-person groups render through one loop. A {@code group} row is an unnamed tri-state checkbox
 * (its id is never submitted as an {@code accountId}); a member row is a real {@code
 * name="accountId"} checkbox. Group membership is markup-driven (space-separated ancestor keys in
 * {@link Row#memberOf()}) so the {@code filter-groups.js} leaf stays agnostic to whether the
 * grouping came from account hierarchy or person ownership (CLAUDE.md §1.6).
 *
 * @param tabs the five picker tabs, in strip order
 * @param panel the active picker's panel
 */
public record RegisterFilterView(List<Tab> tabs, Panel panel) {

  /** Defensively copy the tab list to an immutable list. */
  public RegisterFilterView {
    tabs = List.copyOf(tabs);
  }

  /**
   * One tab in the strip. Only the {@link #active()} tab carries a {@link #tickedCount()}; the rest
   * carry {@code null} (inactive tabs show no count — register §2.3, issue 22).
   *
   * @param param the {@code ?picker=} token, and the {@code hx-get} the tab fetches its panel with
   * @param label the strip label, e.g. {@code Last used}
   * @param active whether this is the picker the register is currently filtered by
   * @param tickedCount how many accounts are ticked in the active picker; {@code null} when
   *     inactive
   */
  public record Tab(String param, String label, boolean active, Integer tickedCount) {}

  /**
   * The active picker's panel: its rows, and whether it should render expanded (a tab switch opens
   * it so the user can pick; a full page render and the post-Apply render leave it collapsed).
   *
   * @param picker the picker this panel belongs to
   * @param pickerParam its {@code ?picker=} token (the hidden field the form submits)
   * @param expanded whether the {@code <details>} renders open
   * @param rows the group-toggle and member rows, in display order
   */
  public record Panel(RegisterPicker picker, String pickerParam, boolean expanded, List<Row> rows) {
    /** Defensively copy the row list to an immutable list. */
    public Panel {
      rows = List.copyOf(rows);
    }
  }

  /**
   * One panel row — a group toggle (a parent account, the {@code Persons} umbrella, or a per-person
   * sub-group) or a selectable leaf checkbox.
   *
   * @param accountId the account's id; submitted as {@code accountId} only for a member row, {@code
   *     0} on a group toggle that is not backed by an account (the person groups)
   * @param label a real account's name, or — under a person — the bare currency code; a group
   *     toggle's heading text
   * @param currencyCode a real account's currency, shown muted after the name; null on group rows
   *     and on person currency leaves (the code is already the label)
   * @param hue the account's register hue for the swatch; nullable
   * @param depth indentation level (0 = top)
   * @param group true → an unnamed tri-state toggle; false → a real {@code name="accountId"}
   *     checkbox
   * @param groupKey this row's own {@code data-group} value when {@link #group()}; else null
   * @param memberOf space-separated {@code data-group} keys of every group this row belongs to (for
   *     a member row, and for a nested group toggle); null when it belongs to no group
   * @param ticked whether the checkbox renders checked
   * @param closed whether this is a closed account — shown with a muted "closed" marker (§2.3a);
   *     always {@code false} for group rows and person currency leaves
   */
  public record Row(
      long accountId,
      String label,
      String currencyCode,
      Integer hue,
      int depth,
      boolean group,
      String groupKey,
      String memberOf,
      boolean ticked,
      boolean closed) {}
}
