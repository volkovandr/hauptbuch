package volkovandr.hauptbuch.analytics;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import volkovandr.hauptbuch.BrowserTest;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The Report page's Category filter in a real browser — {@code filter-groups.js} in node mode
 * (reporting.md §11a.5). Ticking a node stores that node alone; its descendants render ticked and
 * disabled until it is unticked, which gives each of them back its own earlier state.
 */
class ReportHierarchyFilterBrowserTest extends BrowserTest {

  private static final String PARENT = "Leisure";
  private static final String CHILD = "Cinema";
  private static final String OTHER_CHILD = "Books";

  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  private long parentId;
  private long childId;

  @BeforeAll
  void seedCategories() {
    if (settingsService.baseCurrency().isEmpty()) {
      settingsService.setBaseCurrency("EUR");
    }
    parentId = accountService.insertLeaf(PARENT, "expense", null, "EUR").accountId();
    childId = accountService.insertLeaf(CHILD, "expense", parentId, "EUR").accountId();
    accountService.insertLeaf(OTHER_CHILD, "expense", parentId, "EUR");
  }

  /** Load the category matrix Preset and open its Filters group. */
  private void openCategoryFilter() {
    page.navigate(url("/reports/preset/" + Presets.CATEGORY_MONTH_MATRIX_SLUG));
    page.getByText("Filters", new Page.GetByTextOptions().setExact(true)).click();
  }

  private Locator node(String label) {
    return page.locator("#filter-category label.check")
        .filter(
            new Locator.FilterOptions()
                .setHas(page.getByText(label, new Page.GetByTextOptions().setExact(true))))
        .locator("input");
  }

  @Test
  void tickingParentTicksAndLocksEveryDescendant() {
    openCategoryFilter();

    node(PARENT).check();

    for (String child : new String[] {CHILD, OTHER_CHILD}) {
      assertThat(node(child)).isChecked();
      assertThat(node(child)).isDisabled();
    }
  }

  @Test
  void untickingParentGivesEachDescendantBackItsOwnState() {
    openCategoryFilter();
    node(CHILD).check();
    node(PARENT).check();

    node(PARENT).uncheck();

    assertThat(node(CHILD)).isChecked();
    assertThat(node(CHILD)).isEnabled();
    assertThat(node(OTHER_CHILD)).not().isChecked();
    assertThat(node(OTHER_CHILD)).isEnabled();
  }

  @Test
  void applyStoresTheTickedParentAloneNotItsDescendants() {
    openCategoryFilter();
    node(CHILD).check();
    node(PARENT).check();

    page.locator("#filter-category")
        .locator("xpath=ancestor::form")
        .getByRole(
            com.microsoft.playwright.options.AriaRole.BUTTON,
            new Locator.GetByRoleOptions().setName("Apply"))
        .click();

    // The disabled (covered) child is not submitted, so the URL names the parent only.
    assertThat(page).hasURL(Pattern.compile(".*filter\\.CATEGORY\\.value=" + parentId + "(&.*)?$"));
    assertThat(page)
        .not()
        .hasURL(Pattern.compile(".*filter\\.CATEGORY\\.value=" + childId + "(&.*)?$"));
  }
}
