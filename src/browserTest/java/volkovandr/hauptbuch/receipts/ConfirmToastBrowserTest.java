package volkovandr.hauptbuch.receipts;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import volkovandr.hauptbuch.BrowserTest;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.Settings;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The "Confirmed!" toast and Ctrl+Backspace in a real browser — {@code keyboard.js} over the
 * server-rendered toasts (receipt issue 38): the jump works on any page while the newest toast is
 * on screen, opens the newest receipt, and leaves word-deletion alone in a text field.
 */
class ConfirmToastBrowserTest extends BrowserTest {

  private static final int LONG_LIFETIME = 20;

  @Autowired JdbcClient jdbcClient;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  private long cash;
  private long category;

  @BeforeAll
  void seedAccounts() {
    if (settingsService.baseCurrency().isEmpty()) {
      settingsService.setBaseCurrency("EUR");
    }
    cash =
        accountService
            .openAccount(
                new AccountDraft(
                    "ToastCash", "asset", null, "EUR", LocalDate.now(), BigDecimal.ZERO))
            .accountId();
    category = accountService.insertLeaf("ToastFood", "expense", null, "EUR").accountId();
  }

  @AfterAll
  void restoreTheDefaultLifetime() {
    settingsService.setConfirmToastSeconds(Settings.DEFAULT_CONFIRM_TOAST_SECONDS);
  }

  private long processedReceipt() {
    long id =
        jdbcClient
            .sql(
                """
                insert into receipt (state, source, original_path, edited_path, edit_recipe,
                                     account_id, total_amount, currency_code, receipt_date)
                values ('processed', 'pc', 'originals/a.jpg', 'edited/a.jpg', '{}',
                        :account, 12.50, 'EUR', :day)
                returning receipt_id
                """)
            .param("account", cash)
            .param("day", LocalDate.now())
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            """
            insert into receipt_line (receipt_id, description, amount, account_id, sort_order)
            values (:id, 'Lunch', 12.50, :category, 0)
            """)
        .param("id", id)
        .param("category", category)
        .update();
    return id;
  }

  private static Pattern receiptUrl(long id) {
    return Pattern.compile(".*/receipts/" + id + "(\\?.*)?$");
  }

  /** Open the receipt and confirm it with Ctrl+Enter, waiting until the redirect has left it. */
  private void confirmFromKeyboard(long id) {
    page.navigate(url("/receipts/" + id));
    assertThat(page.locator("#receipt-editor")).isVisible();
    page.keyboard().press("Control+Enter");
    page.waitForURL(u -> !receiptUrl(id).matcher(u).matches());
  }

  private Locator toasts() {
    return page.locator(".confirm-toast");
  }

  @Test
  void confirmShowsToastAndCtrlBackspaceReopensThatReceipt() {
    settingsService.setConfirmToastSeconds(LONG_LIFETIME);
    long id = processedReceipt();
    confirmFromKeyboard(id);
    assertThat(toasts().first()).isVisible();

    page.keyboard().press("Control+Backspace");

    page.waitForURL(receiptUrl(id));
    assertThat(page.locator("button:has-text(\"Reopen\")").first()).isVisible();
  }

  @Test
  void ctrlBackspaceWorksOnTheReceiptsMainPageToo() {
    settingsService.setConfirmToastSeconds(LONG_LIFETIME);
    long id = processedReceipt();
    confirmFromKeyboard(id);

    page.navigate(url("/receipts"));
    assertThat(toasts().first()).isVisible();
    page.keyboard().press("Control+Backspace");

    page.waitForURL(receiptUrl(id));
  }

  @Test
  void withTwoToastsCtrlBackspaceOpensTheMostRecentlyConfirmedReceipt() {
    settingsService.setConfirmToastSeconds(LONG_LIFETIME);
    long first = processedReceipt();
    long second = processedReceipt();
    confirmFromKeyboard(first);
    confirmFromKeyboard(second);
    assertThat(toasts()).hasCount(2);

    page.keyboard().press("Control+Backspace");

    page.waitForURL(receiptUrl(second));
  }

  @Test
  void theToastLinkOpensTheReceipt() {
    settingsService.setConfirmToastSeconds(LONG_LIFETIME);
    long id = processedReceipt();
    confirmFromKeyboard(id);

    toasts().first().locator("a").click();

    page.waitForURL(receiptUrl(id));
    assertThat(page).hasURL(receiptUrl(id));
  }

  @Test
  void withoutToastCtrlBackspaceDoesNothing() {
    long id = processedReceipt();
    page.navigate(url("/receipts/" + id));
    assertThat(toasts()).hasCount(0);

    page.keyboard().press("Control+Backspace");
    page.waitForTimeout(300);

    assertThat(page).hasURL(receiptUrl(id));
  }

  @Test
  void onceTheToastHasFadedCtrlBackspaceDoesNothing() {
    settingsService.setConfirmToastSeconds(1);
    long id = processedReceipt();
    confirmFromKeyboard(id);

    page.waitForTimeout(1500);
    String landed = page.url();
    page.keyboard().press("Control+Backspace");
    page.waitForTimeout(300);

    assertThat(page).hasURL(landed);
  }

  @Test
  void insideTextFieldCtrlBackspaceStillDeletesWord() {
    settingsService.setConfirmToastSeconds(LONG_LIFETIME);
    long confirmed = processedReceipt();
    long other = processedReceipt();
    confirmFromKeyboard(confirmed);
    page.navigate(url("/receipts/" + other));
    Locator note = page.locator("#receipt-editor textarea[name=note]");
    note.fill("one two");
    note.focus();

    page.keyboard().press("Control+Backspace");

    assertThat(note).hasValue("one ");
    assertThat(page).hasURL(receiptUrl(other));
  }
}
