package volkovandr.hauptbuch.receipts;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * The mobile capture surface (§4, extended by §9b): a single standalone page — not the desktop
 * shell — where the phone shoots a photo (camera-first, plain file input), sees a thumbnail grid of
 * all its own receipts (last 90 days, newest first), taps through to the full-scale original, and
 * instant-deletes a bad {@code new} shot.
 *
 * <p>Lives in {@code receipts}: the feature module owns its screens (CLAUDE.md §3). The phone is a
 * capture device, not a finance console — no parsed figures, no transaction detail.
 */
@Controller
class ReceiptCaptureController {

  private static final String CAPTURE_VIEW = "receipt-capture";
  private static final String CAPTURE_PATH = "/receipts/capture";
  private static final String REDIRECT_TO_CAPTURE = "redirect:" + CAPTURE_PATH;

  private final ReceiptService receiptService;

  ReceiptCaptureController(ReceiptService receiptService) {
    this.receiptService = receiptService;
  }

  /** The capture page: the shoot button and the thumbnail grid of recent receipts. */
  @GetMapping(CAPTURE_PATH)
  String capture(Model model) {
    model.addAttribute("receipts", receiptService.forMobile());
    model.addAttribute("title", "Capture · Hauptbuch");
    return CAPTURE_VIEW;
  }

  /**
   * Receive a captured photo, store it, and return to the grid (PRG). A wrong format or oversize
   * upload re-renders the page with the rejection message and a 400, rather than a bare error.
   */
  @PostMapping("/receipts")
  String upload(
      @RequestParam("image") MultipartFile image, Model model, HttpServletResponse response) {
    try {
      receiptService.capture(bytesOf(image), ReceiptService.SOURCE_MOBILE);
    } catch (ReceiptFormatException e) {
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      model.addAttribute("error", e.getMessage());
      model.addAttribute("receipts", receiptService.forMobile());
      model.addAttribute("title", "Capture · Hauptbuch");
      return CAPTURE_VIEW;
    }
    return REDIRECT_TO_CAPTURE;
  }

  /**
   * Instant-delete a {@code new} scan from the phone (the top rung of the delete ladder, §9b): the
   * row is soft-deleted and its files removed. Mobile delete is narrowed to {@code new} — every
   * other state is a PC concern — so a non-{@code new} receipt is left untouched.
   */
  @PostMapping("/receipts/capture/{id}/delete")
  String deleteFromPhone(@PathVariable long id) {
    receiptService
        .findById(id)
        .filter(r -> ReceiptState.deletesInstantly(r.state()))
        .ifPresent(r -> receiptService.delete(id, true));
    return REDIRECT_TO_CAPTURE;
  }

  private static byte[] bytesOf(MultipartFile image) {
    if (image == null || image.isEmpty()) {
      throw new ReceiptFormatException("No photo was attached — take a shot and try again.");
    }
    try {
      return image.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the uploaded image", e);
    }
  }
}
