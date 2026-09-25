package volkovandr.hauptbuch.ledger;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import volkovandr.hauptbuch.BrowserTest;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;

/**
 * The register's keyboard layer in a real browser — {@code keyboard.js} (tech-stack §4.3, register
 * §2.1, §3.1–§3.10): row navigation, the "n" and "s" shortcuts, the newest-at-bottom scroll, the
 * select-all amount field, the split panel's live readout, and the tag chips.
 */
class RegisterKeyboardBrowserTest extends BrowserTest {

  private static final String ACCOUNT = "KbdBank";
  private static final String CATEGORY = "KbdFood";
  private static final int SPENDS = 40;
  private static final Pattern SELECTED = Pattern.compile(".*row--selected.*");
  private static final Pattern EDITING = Pattern.compile(".*register__row--editing.*");

  @Autowired AccountService accountService;
  @Autowired LedgerService ledgerService;
  @Autowired SettingsService settingsService;

  @BeforeAll
  void seedRegister() {
    if (settingsService.baseCurrency().isEmpty()) {
      settingsService.setBaseCurrency("EUR");
    }
    LocalDate start = LocalDate.now().minusDays(SPENDS + 5);
    long account =
        accountService
            .openAccount(
                new AccountDraft(ACCOUNT, "asset", null, "EUR", start, new BigDecimal("1000")))
            .accountId();
    long category = accountService.insertLeaf(CATEGORY, "expense", null, "EUR").accountId();
    // Enough rows to overflow the register's scroll box, so "opens at the bottom" means something.
    for (int day = 1; day <= SPENDS; day++) {
      spend(start.plusDays(day), account, category);
    }
  }

  private void spend(LocalDate date, long account, long category) {
    ledgerService.recordTransaction(
        new TransactionDraft(
            date,
            null,
            "spend",
            "confirmed",
            List.of(
                PostingDraft.of(account, new BigDecimal("-5")),
                PostingDraft.of(category, new BigDecimal("5")))));
  }

  private void openRegister() {
    page.navigate(url("/register"));
  }

  private Locator rows() {
    return page.locator("#register-rows [data-kbd-row]");
  }

  private Locator amountField() {
    return page.locator("#entry-dock input[name=amount]");
  }

  /** Type the dock's category and amount, as an operator would before splitting it. */
  private void fillDock(String amount) {
    page.locator("#entry-account").fill(ACCOUNT + " (EUR)");
    page.locator("#entry-account").dispatchEvent("change");
    page.locator("#entry-category").fill(CATEGORY);
    page.locator("#entry-category").dispatchEvent("change");
    amountField().fill(amount);
  }

  /** Move focus out of every field, so single-key shortcuts apply. */
  private void leaveFields() {
    page.evaluate("document.activeElement.blur()");
  }

  @Test
  void arrowKeysWalkTheRowsAndEnterOpensTheSelectedTransactionInTheDock() {
    openRegister();

    page.keyboard().press("ArrowDown");
    assertThat(rows().nth(0)).hasClass(SELECTED);
    page.keyboard().press("ArrowDown");
    Locator second = rows().nth(1);
    assertThat(second).hasClass(SELECTED);
    assertThat(rows().nth(0)).not().hasClass(SELECTED);

    page.keyboard().press("Enter");

    // Enter clicks the row's edit button: the dock loads that transaction, and the row is marked.
    String transactionId = second.getAttribute("data-transaction-id");
    assertThat(page.locator("#entry-dock input[name=transactionId]")).hasValue(transactionId);
    assertThat(page.locator("[data-transaction-id='" + transactionId + "']")).hasClass(EDITING);
  }

  @Test
  void newEntryShortcutJumpsToTheDockButNotWhileTyping() {
    openRegister();

    page.keyboard().press("n");
    assertThat(page.locator("#entry-date")).isFocused();

    page.locator("#entry-note").focus();
    page.keyboard().type("n");
    assertThat(page.locator("#entry-note")).isFocused();
    assertThat(page.locator("#entry-note")).hasValue("n");
  }

  @Test
  void registerOpensScrolledToTheNewestRow() {
    openRegister();

    Object gap =
        page.locator(".register-scroll")
            .evaluate("box => box.scrollHeight - box.clientHeight - box.scrollTop");
    Object overflow =
        page.locator(".register-scroll").evaluate("box => box.scrollHeight > box.clientHeight");

    assertThat(overflow).isEqualTo(true);
    assertThat(((Number) gap).doubleValue()).isLessThan(2.0);
  }

  @Test
  void amountFieldSelectsItsWholeValueOnFirstClickOnly() {
    openRegister();
    amountField().fill("12,50");
    page.locator("#entry-note").click();

    amountField().click();
    assertThat(amountField().evaluate("f => [f.selectionStart, f.selectionEnd]"))
        .isEqualTo(List.of(0, 5));

    // A second click in the already-focused field just places the caret.
    amountField().click();
    assertThat(amountField().evaluate("f => f.selectionStart === f.selectionEnd")).isEqualTo(true);
  }

  @Test
  void splitShortcutOpensThePanelThatReadsTheRemainingAsTheOperatorTypes() {
    openRegister();
    fillDock("20");
    leaveFields();

    page.keyboard().press("s");

    // The panel opens seeded from the dock, with the cursor in the first line's amount.
    Locator lineAmount = page.locator("[data-split-panel] [data-split-amount]").first();
    assertThat(lineAmount).isFocused();

    // "15.50" reads as 15,50 — the last separator is the decimal point, as on the server.
    lineAmount.fill("15.50");
    assertThat(page.locator("[data-split-remaining-value]")).hasText("4,50");
    assertThat(page.locator("[data-split-save]")).hasText("Save and update amount");

    lineAmount.fill("20");
    assertThat(page.locator("[data-split-remaining-value]")).hasText("0,00");
    assertThat(page.locator("[data-split-save]")).hasText("Save");
  }

  @Test
  void enterCommitsTagChipAndBackspaceOnTheEmptyInputRemovesIt() {
    openRegister();
    Locator tags = page.locator("#entry-tags");
    Locator chips = page.locator("#entry-tag-chips [data-tag-chip]");

    tags.fill("KbdTrip");
    tags.press("Enter");

    assertThat(chips).hasCount(1);
    assertThat(chips.first()).containsText("KbdTrip");
    assertThat(tags).hasValue("");
    assertThat(tags).isFocused();

    tags.press("Backspace");
    assertThat(chips).hasCount(0);
  }
}
