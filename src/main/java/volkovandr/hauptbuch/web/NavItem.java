package volkovandr.hauptbuch.web;

import java.util.List;

/**
 * One entry in the navigation shell: a label and the path it links to.
 *
 * <p>The shell lists the app's sections. The frequently used ones sit in the bar; the rarely used
 * ones ({@link #overflow}) sit in the trailing {@code ⋯} menu. {@link #current} marks the active
 * section for highlighting.
 *
 * @param label the human-readable section name
 * @param path the URL path the item links to
 * @param current whether this item is the currently-active section
 * @param overflow whether this item lives in the {@code ⋯} menu rather than the bar
 */
public record NavItem(String label, String path, boolean current, boolean overflow) {

  /** The static set of sections, in display order (bar items first, then the {@code ⋯} menu). */
  static final List<NavItem> SECTIONS =
      List.of(
          new NavItem("Register", "/register", false, false),
          new NavItem("Receipts", "/receipts", false, false),
          new NavItem("Reports", "/reports", false, false),
          new NavItem("Recurring", "/recurring", false, false),
          new NavItem("People", "/people", false, false),
          new NavItem("Accounts", "/accounts", false, true),
          new NavItem("Categories", "/categories", false, true),
          new NavItem("Import", "/import", false, true),
          new NavItem("Settings", "/settings", false, true));

  /** This item with {@code current} set to whether its path matches {@code activePath}. */
  NavItem markedCurrentFor(String activePath) {
    return new NavItem(label, path, path.equals(activePath), overflow);
  }

  /** The sections list with the item matching {@code activePath} marked current. */
  public static List<NavItem> sectionsFor(String activePath) {
    return SECTIONS.stream().map(item -> item.markedCurrentFor(activePath)).toList();
  }
}
