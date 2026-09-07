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
   * The active picker's panel, split into two columns so a long real-account list and a long person
   * list sit side by side rather than stacked (owner feedback 2026-09-07). The template renders one
   * column when the other is empty (Open/Closed have no people; Persons has no accounts) and both
   * side by side for {@code All} / {@code Last used}.
   *
   * @param picker the picker this panel belongs to
   * @param pickerParam its {@code ?picker=} token (the hidden field the form submits)
   * @param expanded whether the {@code <details>} renders open (a tab switch opens it; a full page
   *     render and the post-Apply render leave it collapsed)
   * @param accountRows the real-account column (parent group toggles + leaf checkboxes)
   * @param personRows the person column (the {@code Persons} umbrella, per-person toggles, currency
   *     leaves)
   */
  public record Panel(
      RegisterPicker picker,
      String pickerParam,
      boolean expanded,
      List<Row> accountRows,
      List<Row> personRows) {
    /** Defensively copy the row lists to immutable lists. */
    public Panel {
      accountRows = List.copyOf(accountRows);
      personRows = List.copyOf(personRows);
    }
  }

  /**
   * The muted marker a panel row carries after its label (issue transaction-register-ui/22, /23).
   * The two are mutually exclusive by position — {@link #CLOSED} only ever sits on a closed
   * real-account leaf, {@link #DELETED} only ever on a soft-deleted person's toggle — so one enum,
   * not two booleans.
   */
  public enum Marker {
    /** No marker. */
    NONE,
    /** A closed account — viewable, never bookable (§2.3a). */
    CLOSED,
    /** A soft-deleted person whose debt leaf is still live (issue transaction-register-ui/23). */
    DELETED
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
   * @param marker the muted marker after the label — {@link Marker#CLOSED} on a closed real-account
   *     leaf, {@link Marker#DELETED} on a soft-deleted person's toggle, else {@link Marker#NONE}
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
      Marker marker) {

    /** Whether this row is a closed account — drives the muted "closed" label in the template. */
    public boolean closed() {
      return marker == Marker.CLOSED;
    }

    /** Whether this row is a soft-deleted person — drives the muted "deleted" label. */
    public boolean deleted() {
      return marker == Marker.DELETED;
    }
  }
}
