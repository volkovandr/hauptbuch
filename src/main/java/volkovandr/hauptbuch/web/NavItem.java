package volkovandr.hauptbuch.web;

import java.util.List;

/**
 * One entry in the navigation shell: a label and the path it links to.
 *
 * <p>The shell lists the app's top-level sections. Most point at screens that do not exist yet
 * (they arrive with their stages); they render as nav targets now so the chrome is complete and the
 * shape of the app is legible from stage 4. {@link #current} marks the active section for
 * highlighting.
 *
 * @param label the human-readable section name
 * @param path the URL path the item links to
 * @param current whether this item is the currently-active section
 */
public record NavItem(String label, String path, boolean current) {

  /** The static set of top-level sections, in display order. */
  static final List<NavItem> SECTIONS =
      List.of(
          new NavItem("Register", "/register", false),
          new NavItem("Accounts", "/accounts", false),
          new NavItem("Categories", "/categories", false),
          new NavItem("Receipts", "/receipts", false),
          new NavItem("Reports", "/reports", false),
          new NavItem("Settings", "/settings", false));

  /** This item with {@code current} set to whether its path matches {@code activePath}. */
  NavItem markedCurrentFor(String activePath) {
    return new NavItem(label, path, path.equals(activePath));
  }

  /** The sections list with the item matching {@code activePath} marked current. */
  public static List<NavItem> sectionsFor(String activePath) {
    return SECTIONS.stream().map(item -> item.markedCurrentFor(activePath)).toList();
  }
}
