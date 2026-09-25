package volkovandr.hauptbuch.receipts;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.KeyboardModifier;
import com.microsoft.playwright.options.MouseButton;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import volkovandr.hauptbuch.BrowserTest;

/**
 * The receipt screens' part of the keyboard layer in a real browser — {@code keyboard.js} (receipt
 * doc §4, §5.2, §6): upload on file selection, the list's click / shift-click / ctrl-click
 * selection and its right-click menu, and the processing screen's arrow and Back keys.
 */
class ReceiptKeyboardBrowserTest extends BrowserTest {

  private static final Pattern SELECTED = Pattern.compile(".*receipts__row--selected.*");

  @Autowired ReceiptService receiptService;

  @BeforeAll
  void seedReceipts() {
    for (int i = 0; i < 3; i++) {
      receiptService.capture(ReceiptScans.small(), ReceiptService.SOURCE_PC);
    }
  }

  private Locator receiptRows() {
    return page.locator("#receipt-rows [data-receipt-row]");
  }

  @Test
  void choosingFileOnTheCapturePageUploadsItWithoutAnUploadTap() {
    page.navigate(url("/receipts/capture"));
    int before = page.locator(".capture__grid .tile").count();

    page.waitForNavigation(
        () ->
            page.locator("input[data-autosubmit]")
                .first()
                .setInputFiles(new FilePayload("scan.jpg", "image/jpeg", ReceiptScans.small())));

    assertThat(page.locator(".capture__grid .tile")).hasCount(before + 1);
  }

  @Test
  void clickShiftClickAndCtrlClickSelectReceiptRows() {
    page.navigate(url("/receipts"));
    Locator rows = receiptRows();

    rows.nth(0).click();
    assertThat(page.locator("#receipt-rows .receipts__row--selected")).hasCount(1);

    rows.nth(2).click(new Locator.ClickOptions().setModifiers(List.of(KeyboardModifier.SHIFT)));
    assertThat(page.locator("#receipt-rows .receipts__row--selected")).hasCount(3);

    rows.nth(1)
        .click(new Locator.ClickOptions().setModifiers(List.of(KeyboardModifier.CONTROLORMETA)));
    assertThat(rows.nth(1)).not().hasClass(SELECTED);
    assertThat(page.locator("#receipt-rows .receipts__row--selected")).hasCount(2);
  }

  @Test
  void rightClickOpensTheSelectionMenuAndEscapeClosesIt() {
    page.navigate(url("/receipts"));

    receiptRows().nth(0).click(new Locator.ClickOptions().setButton(MouseButton.RIGHT));

    assertThat(receiptRows().nth(0)).hasClass(SELECTED);
    assertThat(page.locator("#receipt-menu [data-receipt-menu]")).isVisible();

    page.keyboard().press("Escape");
    assertThat(page.locator("#receipt-menu [data-receipt-menu]")).hasCount(0);
  }

  @Test
  void doubleClickOpensTheProcessingScreenWhereArrowDownGoesNextAndBackspaceGoesBack() {
    page.navigate(url("/receipts"));
    String firstId = receiptRows().nth(0).getAttribute("data-receipt-id");
    final String secondId = receiptRows().nth(1).getAttribute("data-receipt-id");

    receiptRows().nth(0).dblclick();
    page.waitForURL(Pattern.compile(".*/receipts/" + firstId + "\\b.*"));

    page.keyboard().press("ArrowDown");
    page.waitForURL(Pattern.compile(".*/receipts/" + secondId + "\\b.*"));

    page.keyboard().press("Backspace");
    page.waitForURL(Pattern.compile(".*/receipts(\\?.*)?$"));
    assertThat(receiptRows().first()).isVisible();
  }
}
