package volkovandr.hauptbuch.receipts;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import volkovandr.hauptbuch.BrowserTest;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The processing screen's dirty guard in a real browser — the inline script in {@code
 * receipt-process.html} (plan §9f): unsaved editor changes warn exactly once before leaving, and a
 * Confirm (which saves server-side) never warns.
 */
class ReceiptDirtyGuardBrowserTest extends BrowserTest {

  private static final Pattern RECEIPT_LIST = Pattern.compile(".*/receipts(\\?.*)?$");

  @Autowired JdbcClient jdbcClient;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  private final List<String> dialogs = new ArrayList<>();
  private boolean acceptDialogs;
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
                    "GuardCash", "asset", null, "EUR", LocalDate.now(), BigDecimal.ZERO))
            .accountId();
    category = accountService.insertLeaf("GuardFood", "expense", null, "EUR").accountId();
  }

  /** A processed receipt with one line that already balances its total, ready to Confirm. */
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

  /**
   * Every dialog the page raised, by type; each is accepted unless {@code acceptDialogs} is off.
   */
  @BeforeEach
  void recordDialogs() {
    dialogs.clear();
    acceptDialogs = true;
    page.onDialog(
        dialog -> {
          dialogs.add(dialog.type());
          if (acceptDialogs) {
            dialog.accept();
          } else {
            dialog.dismiss();
          }
        });
  }

  private void editNote(long id) {
    page.navigate(url("/receipts/" + id));
    page.locator("#receipt-editor textarea[name=note]").fill("changed");
  }

  @Test
  void leavingWithUnsavedChangesAsksOnceNotTwice() {
    long id = processedReceipt();
    editNote(id);

    page.locator("[data-receipt-back]").click();
    page.waitForURL(RECEIPT_LIST);

    assertThat(dialogs).containsExactly("confirm");
  }

  @Test
  void decliningTheAppPromptKeepsThePageAndTheGuard() {
    long id = processedReceipt();
    editNote(id);
    acceptDialogs = false;

    page.locator("[data-receipt-back]").click();

    assertThat(page).hasURL(Pattern.compile(".*/receipts/" + id + "\\b.*"));
  }

  @Test
  void confirmWithUnsavedChangesLeavesWithoutAnyPrompt() {
    long id = processedReceipt();
    editNote(id);

    page.locator("[data-receipt-confirm]").click();
    page.waitForURL(url -> !url.contains("/receipts/" + id));

    assertThat(dialogs).isEmpty();
  }

  @Test
  void savingClearsTheGuardSoLeavingAsksNothing() {
    long id = processedReceipt();
    editNote(id);

    page.locator("[data-split-save]").click();
    assertThat(page.locator("[data-receipt-editor-saved]")).isVisible();
    page.locator("[data-receipt-back]").click();
    page.waitForURL(RECEIPT_LIST);

    assertThat(dialogs).isEmpty();
  }
}
