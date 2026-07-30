package volkovandr.hauptbuch.receipts;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Shared multipart-to-bytes handling for the capture (mobile) and register (PC) upload endpoints.
 */
final class ReceiptUploads {

  private ReceiptUploads() {}

  /**
   * The uploaded image's bytes, or a {@link ReceiptFormatException} when nothing usable was
   * attached — the same user-facing failure the format/size checks raise, so callers handle one
   * exception type.
   */
  static byte[] bytesOf(MultipartFile image) {
    if (image == null || image.isEmpty()) {
      throw new ReceiptFormatException("No image was attached — pick a file and try again.");
    }
    try {
      return image.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the uploaded image", e);
    }
  }
}
