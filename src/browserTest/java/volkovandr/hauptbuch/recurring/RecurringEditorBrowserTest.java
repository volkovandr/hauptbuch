package volkovandr.hauptbuch.recurring;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import volkovandr.hauptbuch.BrowserTest;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The recurring template editor in a real browser: the split panel hosted under {@code
 * /recurring/editor} behaves as it does in the register (register §3.10) — a total typed first
 * fills the first line, and Add line puts the cursor on the new line's category ({@code
 * keyboard.js}).
 */
class RecurringEditorBrowserTest extends BrowserTest {

  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  @BeforeAll
  void seed() {
    if (settingsService.baseCurrency().isEmpty()) {
      settingsService.setBaseCurrency("EUR");
    }
    accountService.openAccount(
        new AccountDraft(
            "RecBank", "asset", null, "EUR", LocalDate.now().minusDays(5), BigDecimal.TEN));
    accountService.insertLeaf("RecStreaming", "expense", null, "EUR");
  }

  private Locator lineAmounts() {
    return page.locator("[data-split-panel] [data-split-amount]");
  }

  /**
   * Wait until htmx has finished swapping and settling, so the panel it just replaced is wired up
   * before the next event is fired at it.
   */
  private void settled() {
    page.waitForFunction(
        "() => !document.querySelector('.htmx-request, .htmx-swapping, .htmx-settling')");
  }

  @Test
  void totalTypedFirstFillsTheFirstLineAndAddLineFocusesTheNewCategory() {
    page.navigate(url("/recurring/new"));
    Locator category = page.locator("input[name=categoryText]").first();
    category.fill("RecStreaming");
    category.dispatchEvent("change");
    assertThat(page.locator("input[name=lineCategoryType]").first()).hasValue("expense");
    settled();

    page.locator("#split-total").fill("12,99");
    page.locator("#split-total").dispatchEvent("change");
    assertThat(lineAmounts().first()).hasValue("12,99");
    settled();

    lineAmounts().first().fill("10");
    page.getByText("＋ Add line").click();

    Locator categories = page.locator("input[name=categoryText]");
    assertThat(categories).hasCount(2);
    assertThat(categories.last()).isFocused();
    assertThat(lineAmounts().last()).hasValue("2,99");
  }
}
