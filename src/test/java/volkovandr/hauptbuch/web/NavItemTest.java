package volkovandr.hauptbuch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The navigation model: bar vs {@code ⋯} menu placement, and which section is current. */
class NavItemTest {

  @Test
  void topLevelSectionsKeepTheAgreedOrder() {
    assertThat(labels(NavItem.sectionsFor("/").stream().filter(i -> !i.overflow()).toList()))
        .containsExactly("Register", "Receipts", "Reports", "Recurring", "People");
  }

  @Test
  void rarelyUsedSectionsLiveInTheOverflowMenu() {
    assertThat(labels(NavItem.sectionsFor("/").stream().filter(NavItem::overflow).toList()))
        .containsExactly("Accounts", "Categories", "Import", "Settings");
  }

  @Test
  void marksOnlyTheItemMatchingTheActivePathCurrent() {
    List<NavItem> sections = NavItem.sectionsFor("/accounts");

    assertThat(sections.stream().filter(NavItem::current).map(NavItem::label))
        .containsExactly("Accounts");
  }

  @Test
  void marksNothingCurrentOnAnUnlistedPath() {
    assertThat(NavItem.sectionsFor("/").stream().anyMatch(NavItem::current)).isFalse();
  }

  private static List<String> labels(List<NavItem> items) {
    return items.stream().map(NavItem::label).toList();
  }
}
