package volkovandr.hauptbuch.receipts;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import volkovandr.hauptbuch.BrowserTest;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The review shortcuts of a processed receipt in a real browser — {@code keyboard.js} (receipt doc
 * §6, issue receipt-processing/37): A, D and C jump to the account, date and next uncategorised
 * category when the operator is not typing, and Ctrl+Enter confirms from anywhere.
 */
class ReceiptReviewShortcutsBrowserTest extends BrowserTest {

  private static final String CATEGORY = "input[name=categoryText]";

  @Autowired JdbcClient jdbcClient;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;

  private long cash;
  private long bank;
  private long category;

  @BeforeAll
  void seedAccounts() {
    if (settingsService.baseCurrency().isEmpty()) {
      settingsService.setBaseCurrency("EUR");
    }
    cash = openAccount("ShortcutCash");
    bank = openAccount("ShortcutBank");
    category = accountService.insertLeaf("ShortcutFood", "expense", null, "EUR").accountId();
  }

  private long openAccount(String name) {
    return accountService
        .openAccount(new AccountDraft(name, "asset", null, "EUR", LocalDate.now(), BigDecimal.ZERO))
        .accountId();
  }

  /**
   * A processed receipt paid from {@code account} (null = undetected), one line per entry of {@code
   * categories}: a category id, or null for a line the analysis left uncategorised. The lines split
   * the 30.00 total evenly.
   */
  private long processedReceipt(Long account, Long... categories) {
    long id =
        jdbcClient
            .sql(
                """
                insert into receipt (state, source, original_path, edited_path, edit_recipe,
                                     account_id, total_amount, currency_code, receipt_date)
                values ('processed', 'pc', 'originals/a.jpg', 'edited/a.jpg', '{}',
                        :account, 30.00, 'EUR', :day)
                returning receipt_id
                """)
            .param("account", account)
            .param("day", LocalDate.now())
            .query(Long.class)
            .single();
    BigDecimal share = new BigDecimal("30.00").divide(BigDecimal.valueOf(categories.length));
    for (int i = 0; i < categories.length; i++) {
      jdbcClient
          .sql(
              """
              insert into receipt_line (receipt_id, description, amount, account_id, sort_order)
              values (:id, :description, :amount, :category, :sort)
              """)
          .param("id", id)
          .param("description", "Item " + i)
          .param("amount", share)
          .param("category", categories[i])
          .param("sort", i)
          .update();
    }
    return id;
  }

  private void open(long receiptId) {
    page.navigate(url("/receipts/" + receiptId));
    assertThat(page.locator("#receipt-editor")).isVisible();
  }

  private String stateOf(long receiptId) {
    return jdbcClient
        .sql("select state from receipt where receipt_id = :id")
        .param("id", receiptId)
        .query(String.class)
        .single();
  }

  private void waitUntilConfirmedAway(long receiptId) {
    page.waitForURL(url -> !Pattern.matches(".*/receipts/" + receiptId + "(\\?.*)?$", url));
  }

  // ── A: the paying account ───────────────────────────────────────────────────

  @Test
  void accountKeyFocusesTheAccountWithItsWholeContentSelectedSoTypingReplacesIt() {
    open(processedReceipt(cash, category));

    page.keyboard().press("a");

    assertThat(page.locator("#receipt-account")).isFocused();
    page.keyboard().type("Z");
    assertThat(page.locator("#receipt-account")).hasValue("Z");
  }

  // ── D: the date ─────────────────────────────────────────────────────────────

  @Test
  void dateKeyFocusesTheDateField() {
    open(processedReceipt(cash, category));

    page.keyboard().press("d");

    assertThat(page.locator("#receipt-date")).isFocused();
  }

  // ── A, D, C are plain letters while typing ──────────────────────────────────

  @Test
  void theLettersTypeNormallyInEveryTextField() {
    open(processedReceipt(cash, category));

    for (String field :
        new String[] {
          "#receipt-editor textarea[name=note]",
          "#receipt-payee",
          "input[name=lineDescription]",
          CATEGORY
        }) {
      Locator input = page.locator(field).first();
      input.fill("");
      input.focus();
      page.keyboard().type("adc");
      assertThat(input).hasValue("adc");
    }
  }

  @Test
  void theLettersWorkFromTheFocusedButton() {
    open(processedReceipt(cash, category));
    page.locator("[data-receipt-confirm]").focus();

    page.keyboard().press("a");

    assertThat(page.locator("#receipt-account")).isFocused();
  }

  @Test
  void theLettersDoNothingWithCtrlHeldSoCopyAndSelectAllKeepWorking() {
    open(processedReceipt(cash, category));

    page.keyboard().press("Control+c");
    page.keyboard().press("Control+d");

    assertThat(page.locator("body")).isFocused();
  }

  // ── C: the next uncategorised item ──────────────────────────────────────────

  @Test
  void categoryKeyJumpsToTheFirstUncategorisedItemAndAgainToTheNext() {
    open(processedReceipt(cash, category, null, null));
    Locator categories = page.locator(CATEGORY);

    page.keyboard().press("c");
    assertThat(categories.nth(1)).isFocused();

    page.evaluate("document.activeElement.blur()");
    page.keyboard().press("c");
    assertThat(categories.nth(2)).isFocused();

    page.evaluate("document.activeElement.blur()");
    page.keyboard().press("c");
    assertThat(categories.nth(1)).isFocused();
  }

  @Test
  void categoryKeyDoesNothingWhenEveryItemHasCategory() {
    open(processedReceipt(cash, category, category));

    page.keyboard().press("c");

    assertThat(page.locator("body")).isFocused();
  }

  // ── Ctrl+Enter: Confirm from anywhere ───────────────────────────────────────

  @Test
  void ctrlEnterInsideTheNoteConfirmsWithoutAddingNewline() {
    long id = processedReceipt(cash, category);
    open(id);
    Locator note = page.locator("#receipt-editor textarea[name=note]");
    note.fill("Full tank");
    note.focus();

    page.keyboard().press("Control+Enter");
    waitUntilConfirmedAway(id);

    assertThat(stateOf(id)).isEqualTo("committed");
    assertThat(
            jdbcClient
                .sql("select note from receipt where receipt_id = :id")
                .param("id", id)
                .query(String.class)
                .single())
        .isEqualTo("Full tank");
  }

  @Test
  void ctrlEnterRightAfterTypingAnotherAccountBooksAgainstTheNewOne() {
    long id = processedReceipt(cash, category);
    open(id);
    page.locator("#receipt-account").fill("ShortcutBank");

    page.keyboard().press("Control+Enter");
    waitUntilConfirmedAway(id);

    Long funding =
        jdbcClient
            .sql(
                """
                select p.account_id from receipt r
                join posting p on p.transaction_id = r.transaction_id
                where r.receipt_id = :id and p.amount < 0
                """)
            .param("id", id)
            .query(Long.class)
            .single();
    assertThat(funding).isEqualTo(bank);
  }

  @Test
  void ctrlEnterWithoutAnAccountIsRefusedLikeTheButton() {
    long id = processedReceipt(null, category);
    open(id);

    page.keyboard().press("Control+Enter");

    assertThat(page.locator("[data-receipt-confirm-problems]")).isVisible();
    assertThat(stateOf(id)).isEqualTo("processed");
  }

  @Test
  void cmdEnterDoesNotConfirm() {
    long id = processedReceipt(cash, category);
    open(id);

    page.keyboard().press("Meta+Enter");
    page.waitForTimeout(300);

    assertThat(page.locator("[data-receipt-confirm]")).isVisible();
    assertThat(stateOf(id)).isEqualTo("processed");
  }
}
