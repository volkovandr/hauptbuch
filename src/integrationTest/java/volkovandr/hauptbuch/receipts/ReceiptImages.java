package volkovandr.hauptbuch.receipts;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The image side of the receipt integration tests: the scan they upload — a tiny real JPEG, so the
 * magic-byte validation and the thumbnailer both see genuine image bytes (§9b) — and the throwaway
 * root it is stored under. Shared because several test classes were carrying byte-identical copies
 * of both.
 */
final class ReceiptImages {

  private static final int WIDTH = 120;
  private static final int HEIGHT = 160;

  private ReceiptImages() {}

  /** The multipart part a capture/pre-process POST sends. */
  static MockMultipartFile jpegPart() {
    return new MockMultipartFile("image", "photo.jpg", "image/jpeg", jpegBytes());
  }

  /** The baked part a pre-process POST sends — the same bytes under the edited image's name. */
  static MockMultipartFile editedJpegPart() {
    return new MockMultipartFile("image", "edited.jpg", "image/jpeg", jpegBytes());
  }

  /** The bytes on their own, for a direct store. */
  static byte[] jpegBytes() {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "jpg", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** A throwaway storage root, so a test never writes into the dev receipts directory. */
  static Path tempStorageRoot() {
    try {
      return Files.createTempDirectory("hauptbuch-receipts-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
