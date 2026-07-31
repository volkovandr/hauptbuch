package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tier: {@link ReceiptStorage}'s on-disk behaviour against a temp directory (no Spring, no
 * Postgres) — the path scheme, collision suffix, magic-byte validation, size cap, and thumbnail
 * self-heal (§9b). Real image bytes are synthesised with {@link ImageIO} so the magic bytes are
 * genuine.
 */
class ReceiptStorageTest {

  private static final ZoneId ZONE = ZoneId.of("UTC");
  // 2026-07-30 14:30:22.123 UTC → stem "20260730-143022123".
  private static final Instant FIXED = Instant.parse("2026-07-30T14:30:22.123Z");

  private ReceiptStorage storageAt(Path root, Clock clock) {
    return new ReceiptStorage(new ReceiptStorageProperties(root), clock);
  }

  private ReceiptStorage storageAt(Path root) {
    return storageAt(root, Clock.fixed(FIXED, ZONE));
  }

  private static byte[] image(String format) {
    return image(format, 200, 120);
  }

  private static byte[] image(String format, int width, int height) {
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(img, format, out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  @Test
  void storesJpegUnderTheTimestampSchemeWithAnEagerThumbnail(@TempDir Path root) {
    ReceiptStorage storage = storageAt(root);

    String rel = storage.storeOriginal(image("jpg"));

    assertThat(rel).isEqualTo("originals/2026/07/20260730-143022123.jpg");
    assertThat(Files.exists(root.resolve(rel))).isTrue();
    // Thumbnail generated eagerly, always JPEG, same stem under thumbs/.
    assertThat(Files.exists(root.resolve("thumbs/2026/07/20260730-143022123.jpg"))).isTrue();
  }

  @Test
  void storesPngWithPngExtension(@TempDir Path root) {
    ReceiptStorage storage = storageAt(root);

    String rel = storage.storeOriginal(image("png"));

    assertThat(rel).isEqualTo("originals/2026/07/20260730-143022123.png");
    assertThat(Files.exists(root.resolve(rel))).isTrue();
  }

  @Test
  void collidingTimestampsGetNumericSuffixes(@TempDir Path root) {
    // A fixed clock makes both stores compute the same stem, forcing the collision path.
    ReceiptStorage storage = storageAt(root);

    String first = storage.storeOriginal(image("jpg"));
    String second = storage.storeOriginal(image("jpg"));
    String third = storage.storeOriginal(image("jpg"));

    assertThat(first).isEqualTo("originals/2026/07/20260730-143022123.jpg");
    assertThat(second).isEqualTo("originals/2026/07/20260730-143022123-2.jpg");
    assertThat(third).isEqualTo("originals/2026/07/20260730-143022123-3.jpg");
  }

  @Test
  void rejectsNonImageBytesByMagicBytes(@TempDir Path root) {
    ReceiptStorage storage = storageAt(root);

    assertThatThrownBy(
            () -> storage.storeOriginal("%PDF-1.7 not an image".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(ReceiptFormatException.class)
        .hasMessageContaining("JPEG and PNG");
  }

  @Test
  void rejectsOversizedUploads(@TempDir Path root) {
    byte[] tooBig = new byte[(int) ReceiptStorage.MAX_BYTES + 1];
    // Give it a valid JPEG header so the size check, not the format check, is what rejects it.
    tooBig[0] = (byte) 0xFF;
    tooBig[1] = (byte) 0xD8;
    tooBig[2] = (byte) 0xFF;

    ReceiptStorage storage = storageAt(root);
    assertThatThrownBy(() -> storage.storeOriginal(tooBig))
        .isInstanceOf(ReceiptFormatException.class)
        .hasMessageContaining("15 MB");
  }

  @Test
  void readImageReturnsTheStoredOriginalBytes(@TempDir Path root) {
    ReceiptStorage storage = storageAt(root);
    byte[] bytes = image("jpg");
    String rel = storage.storeOriginal(bytes);

    assertThat(storage.readImage(rel)).isEqualTo(bytes);
  }

  @Test
  void thumbnailSelfHealsWhenTheFileIsDeleted(@TempDir Path root) throws IOException {
    ReceiptStorage storage = storageAt(root);
    String rel = storage.storeOriginal(image("jpg"));
    Path thumb = root.resolve("thumbs/2026/07/20260730-143022123.jpg");

    // Simulate wiping the thumbs/ tree to force a style/size regeneration.
    Files.delete(thumb);
    assertThat(Files.exists(thumb)).isFalse();

    byte[] regenerated = storage.readThumbnail(rel, null);

    assertThat(regenerated).isNotEmpty();
    assertThat(Files.exists(thumb)).isTrue();
  }

  @Test
  void storeEditedWritesTheEditedTreeAndRegeneratesTheThumbnail(@TempDir Path root) {
    ReceiptStorage storage = storageAt(root);
    String original = storage.storeOriginal(image("jpg"));

    String edited = storage.storeEdited(original, image("jpg"));

    // Same stem under edited/, always .jpg (the bake is JPEG).
    assertThat(edited).isEqualTo("edited/2026/07/20260730-143022123.jpg");
    assertThat(Files.exists(root.resolve(edited))).isTrue();
    // The preview reflects the edit: the thumbnail (same stem under thumbs/) is regenerated.
    assertThat(Files.exists(root.resolve("thumbs/2026/07/20260730-143022123.jpg"))).isTrue();
  }

  @Test
  void storeEditedOverwritesInPlaceOnReEdit(@TempDir Path root) {
    ReceiptStorage storage = storageAt(root);
    String original = storage.storeOriginal(image("jpg"));

    String first = storage.storeEdited(original, image("jpg"));
    // A visibly different JPEG payload (different dimensions ⇒ different bytes) proves the
    // overwrite.
    byte[] reEdited = image("jpg", 80, 80);
    String second = storage.storeEdited(original, reEdited);

    // Re-edit reuses the same path (overwrite in place), not a new suffixed file.
    assertThat(second).isEqualTo(first);
    assertThat(storage.readImage(second)).isEqualTo(reEdited);
  }

  @Test
  void storeEditedRejectsNonJpegBytes(@TempDir Path root) {
    ReceiptStorage storage = storageAt(root);
    String original = storage.storeOriginal(image("jpg"));

    assertThatThrownBy(() -> storage.storeEdited(original, image("png")))
        .isInstanceOf(ReceiptFormatException.class)
        .hasMessageContaining("JPEG");
  }

  @Test
  void discardEditedRemovesTheEditedFileAndRegeneratesTheThumbnailFromTheOriginal(
      @TempDir Path root) {
    ReceiptStorage storage = storageAt(root);
    String original = storage.storeOriginal(image("jpg"));
    String edited = storage.storeEdited(original, image("jpg"));
    Path editedFile = root.resolve(edited);
    assertThat(Files.exists(editedFile)).isTrue();

    storage.discardEdited(original, edited);

    assertThat(Files.exists(editedFile)).isFalse();
    // The thumbnail is regenerated (from the original) — still present, original untouched.
    assertThat(Files.exists(root.resolve("thumbs/2026/07/20260730-143022123.jpg"))).isTrue();
    assertThat(Files.exists(root.resolve(original))).isTrue();
    // Idempotent: discarding again (edited already gone) is a no-op, not an error.
    storage.discardEdited(original, edited);
  }

  @Test
  void deleteFilesRemovesOriginalAndThumbnailIdempotently(@TempDir Path root) {
    ReceiptStorage storage = storageAt(root);
    String rel = storage.storeOriginal(image("jpg"));
    Path original = root.resolve(rel);
    Path thumb = root.resolve("thumbs/2026/07/20260730-143022123.jpg");

    storage.deleteFiles(rel, null);

    assertThat(Files.exists(original)).isFalse();
    assertThat(Files.exists(thumb)).isFalse();
    // Idempotent: a second delete of the same (now-missing) files is a no-op, not an error.
    storage.deleteFiles(rel, null);
  }
}
