package volkovandr.hauptbuch.ledger;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import volkovandr.hauptbuch.BrowserTest;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;

/**
 * The register's account filter in a real browser — {@code filter-groups.js} in group mode
 * (register §2.3, issue transaction-register-ui/22). Seeds one standalone account next to a parent
 * with two children: the shape register issue 26 broke, where All/None skipped the standalone
 * account and every Apply dropped the accounts inside the hierarchy.
 */
class RegisterAccountFilterBrowserTest extends BrowserTest {

  private static final String STANDALONE = "BankAaa";
  private static final String PARENT = "Pockets";
  private static final String CHILD_EUR = "Pocket-EUR";
  private static final String CHILD_CHF = "Pocket-CHF";
  private static final String ACCOUNT_CHECKBOXES = "[data-filter-groups] input[name=accountId]";

  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  @BeforeAll
  void seedAccounts() {
    if (settingsService.baseCurrency().isEmpty()) {
      settingsService.setBaseCurrency("EUR");
    }
    // Opened a month ago, so each opening balance falls inside the register's default range and
    // the default "Last used" picker lists the account.
    LocalDate opened = LocalDate.now().minusMonths(1);
    open(STANDALONE, null, "EUR", opened, "100");
    long parent = open(PARENT, null, "EUR", opened, null);
    open(CHILD_EUR, parent, "EUR", opened, "20");
    open(CHILD_CHF, parent, "CHF", opened, "30");
  }

  private long open(String name, Long parentId, String currency, LocalDate opened, String balance) {
    BigDecimal openingBalance = balance == null ? null : new BigDecimal(balance);
    return accountService
        .openAccount(new AccountDraft(name, "asset", parentId, currency, opened, openingBalance))
        .accountId();
  }

  /** Load the register and open the account panel. */
  private void openPanel() {
    page.navigate(url("/register"));
    page.getByText("Choose accounts").click();
  }

  private Locator checkbox(String accountName) {
    return page.locator("[data-filter-groups] label.check")
        .filter(
            new Locator.FilterOptions()
                .setHas(page.getByText(accountName, new Page.GetByTextOptions().setExact(true))))
        .locator("input");
  }

  private void apply() {
    page.getByRole(
            com.microsoft.playwright.options.AriaRole.BUTTON,
            new Page.GetByRoleOptions().setName("Apply"))
        .click();
    page.waitForLoadState();
  }

  private void assertEveryAccountTicked() {
    for (String name : new String[] {STANDALONE, CHILD_EUR, CHILD_CHF}) {
      assertThat(checkbox(name)).isChecked();
    }
  }

  @Test
  void noneUnticksEveryAccountIncludingStandaloneOnesAndAllTicksThemBack() {
    openPanel();

    page.locator("[data-filter-none]").click();
    assertThat(page.locator(ACCOUNT_CHECKBOXES + ":checked")).hasCount(0);

    page.locator("[data-filter-all]").click();
    assertThat(page.locator(ACCOUNT_CHECKBOXES + ":not(:checked)")).hasCount(0);
  }

  @Test
  void applyingWithEveryAccountTickedKeepsTheAccountsInsideHierarchies() {
    openPanel();
    page.locator("#fromDate").fill(LocalDate.now().minusMonths(2).toString());

    apply();

    // All ticked: the tidy-up omits every id, so the server re-resolves the whole picker.
    assertThat(page.url()).doesNotContain("accountId");
    page.getByText("Choose accounts").click();
    assertEveryAccountTicked();
    assertThat(page.locator("#register-body")).containsText(CHILD_CHF);
  }

  @Test
  void untickingOneAccountSubmitsEveryOtherAccount() {
    openPanel();
    checkbox(STANDALONE).uncheck();

    apply();

    page.getByText("Choose accounts").click();
    assertThat(checkbox(STANDALONE)).not().isChecked();
    assertThat(checkbox(CHILD_EUR)).isChecked();
    assertThat(checkbox(CHILD_CHF)).isChecked();
  }

  @Test
  void parentToggleTicksOnlyItsOwnChildrenAndShowsPartialSelection() {
    openPanel();
    Locator parentToggle = checkbox(PARENT);

    parentToggle.uncheck();
    assertThat(checkbox(CHILD_EUR)).not().isChecked();
    assertThat(checkbox(CHILD_CHF)).not().isChecked();
    assertThat(checkbox(STANDALONE)).isChecked();

    checkbox(CHILD_EUR).check();
    assertThat(parentToggle.evaluate("toggle => toggle.indeterminate")).isEqualTo(true);
  }
}
