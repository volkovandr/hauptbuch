package volkovandr.hauptbuch.receipts;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import volkovandr.hauptbuch.BrowserTest;

/**
 * The receipt pre-process editor in a real browser — {@code receipt-editor.js} over Cropper.js
 * (receipt doc §6.1): entering and leaving edit mode, the unsaved-changes guard, and Save baking
 * the edited JPEG client-side — downscaled to the long-edge cap, filters in its pixels, the recipe
 * replayed on the next edit.
 */
class ReceiptEditorBrowserTest extends BrowserTest {

  private static final int LONG_EDGE_CAP = 1568;
  private static final int JPEG_TOLERANCE = 8;

  @Autowired ReceiptService receiptService;

  private long capture(int width, int height, Color color) {
    return receiptService
        .capture(ReceiptScans.jpeg(width, height, color), ReceiptService.SOURCE_PC)
        .receiptId();
  }

  private Locator editor() {
    return page.locator("[data-receipt-edit]");
  }

  private Locator readView() {
    return page.locator("[data-receipt-read]");
  }

  /** Open the receipt's processing screen and enter edit mode, waiting for Cropper to be ready. */
  private void startEditing(long receiptId) {
    page.navigate(url("/receipts/" + receiptId));
    page.locator("[data-receipt-edit-start]").first().click();
    assertThat(page.locator(".cropper-crop-box")).isVisible();
  }

  /** Move a range slider the way a drag does: set its value and fire `input`. */
  private void slide(String selector, String value) {
    page.locator(selector)
        .evaluate(
            "(slider, value) => { slider.value = value;"
                + " slider.dispatchEvent(new Event('input', { bubbles: true })); }",
            value);
  }

  private void saveAndWait(long receiptId) {
    page.waitForNavigation(() -> page.locator("[data-receipt-save]").click());
    page.waitForURL(Pattern.compile(".*/receipts/" + receiptId + "\\b.*"));
  }

  private BufferedImage editedImage(long receiptId) {
    byte[] bytes = page.request().get(url("/receipts/" + receiptId + "/edited")).body();
    return ReceiptScans.read(bytes);
  }

  @Test
  void prepareForAnalysisOpensTheCropperAndCancelWithoutChangesGoesStraightBack() {
    long id = capture(400, 300, Color.WHITE);

    startEditing(id);
    assertThat(editor()).isVisible();
    assertThat(readView()).isHidden();

    page.locator("[data-receipt-cancel]").click();

    assertThat(editor()).isHidden();
    assertThat(readView()).isVisible();
    assertThat(page.locator(".cropper-container")).hasCount(0);
  }

  @Test
  void cancelAfterAnAdjustmentAsksFirstAndDecliningKeepsTheEditor() {
    long id = capture(400, 300, Color.WHITE);
    startEditing(id);
    page.locator("[data-crop-rotate-right]").click();

    page.onceDialog(Dialog::dismiss);
    page.locator("[data-receipt-cancel]").click();
    assertThat(editor()).isVisible();

    page.onceDialog(Dialog::accept);
    page.locator("[data-receipt-cancel]").click();
    assertThat(editor()).isHidden();
    assertThat(readView()).isVisible();
  }

  @Test
  void saveBakesJpegCappedAtTheLongEdgeAndTheNextEditReplaysTheRecipe() {
    // The initial crop box covers a quarter of each side: 2000 × 1500, over the cap.
    long id = capture(8000, 6000, Color.WHITE);
    startEditing(id);
    slide("[data-crop-tilt]", "5");
    assertThat(page.locator("[data-receipt-downscale]")).isVisible();

    saveAndWait(id);

    assertThat(receiptService.findById(id).orElseThrow().state()).isEqualTo("pre_processed");
    BufferedImage edited = editedImage(id);
    assertThat(Math.max(edited.getWidth(), edited.getHeight())).isEqualTo(LONG_EDGE_CAP);

    startEditing(id);
    assertThat(page.locator("[data-crop-tilt]")).hasValue("5");
  }

  @Test
  void grayscaleIsBakedIntoTheSavedPixels() {
    long id = capture(400, 300, Color.RED);
    startEditing(id);
    assertThat(page.locator("[data-crop-grayscale]")).isChecked();

    saveAndWait(id);

    BufferedImage edited = editedImage(id);
    Color centre = new Color(edited.getRGB(edited.getWidth() / 2, edited.getHeight() / 2));
    assertThat(Math.abs(centre.getRed() - centre.getGreen())).isLessThanOrEqualTo(JPEG_TOLERANCE);
    assertThat(Math.abs(centre.getRed() - centre.getBlue())).isLessThanOrEqualTo(JPEG_TOLERANCE);
  }
}
