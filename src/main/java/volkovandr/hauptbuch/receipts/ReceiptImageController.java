package volkovandr.hauptbuch.receipts;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves receipt image bytes for both surfaces (§4 mobile grid + tap-through, §5 PC register
 * thumbnails): the small self-healing thumbnail and the full-scale original. Lives in {@code
 * receipts} — the feature module owns its screens (CLAUDE.md §3). A missing receipt is a 404.
 */
@Controller
class ReceiptImageController {

  private final ReceiptService receiptService;

  ReceiptImageController(ReceiptService receiptService) {
    this.receiptService = receiptService;
  }

  /** The self-healing thumbnail (always JPEG) — the register/grid preview. */
  @GetMapping("/receipts/{id}/thumb")
  ResponseEntity<byte[]> thumbnail(@PathVariable long id) {
    return receiptService
        .thumbnailBytes(id)
        .map(bytes -> image(bytes, MediaType.IMAGE_JPEG))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** The full-scale original scan — the mobile tap-through and PC detail image. */
  @GetMapping("/receipts/{id}/image")
  ResponseEntity<byte[]> original(@PathVariable long id) {
    return receiptService
        .findById(id)
        .flatMap(receipt -> receiptService.originalBytes(id).map(bytes -> serve(receipt, bytes)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static ResponseEntity<byte[]> serve(Receipt receipt, byte[] bytes) {
    MediaType type =
        receipt.originalPath().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
    return image(bytes, type);
  }

  private static ResponseEntity<byte[]> image(byte[] bytes, MediaType type) {
    return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noCache()).body(bytes);
  }
}
