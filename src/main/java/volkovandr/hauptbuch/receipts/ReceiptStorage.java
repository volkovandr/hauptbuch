package volkovandr.hauptbuch.receipts;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * The on-disk home of receipt images (ARCH-07). Owns the file tree the database only references by
 * root-relative path: {@code originals/<yyyy>/<MM>/<stem>.<ext>} (the immutable raw scan), the
 * parallel {@code edited/} tree (the pre-processed derivative, 9c), and {@code thumbs/} (small
 * previews). The stem is a capture timestamp shared across all three trees, so a receipt's three
 * files line up by name.
 *
 * <p>ARCH-07 means the original is <em>never edited in place</em>; it does not forbid deleting a
 * dead scan wholesale (the 9b delete ladder), which {@link #deleteFiles} does.
 *
 * <p>Thumbnails are generated eagerly at upload and <em>self-heal at serve time</em>: a missing
 * thumbnail is regenerated from the edited-else-original image and stored, so deleting the whole
 * {@code thumbs/} tree is the sanctioned way to force regeneration after a size/style change.
 */
@Component
public class ReceiptStorage {

  /** Hard multipart cap (§9b): 15 MB. Enforced here so the message is ours, not the container's. */
  static final long MAX_BYTES = 15L * 1024 * 1024;

  /** Longest edge of a generated thumbnail, in pixels (§9b: ~320). */
  static final int THUMB_MAX_DIM = 320;

  private static final String THUMB_FORMAT = "jpg";
  private static final DateTimeFormatter STEM = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
  private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

  private final Path root;
  private final Clock clock;

  ReceiptStorage(ReceiptStorageProperties properties, Clock clock) {
    this.root = properties.storageRoot().toAbsolutePath().normalize();
    this.clock = clock;
  }

  /**
   * Validate an uploaded image (JPEG/PNG by magic bytes, within the size cap) and write it as a new
   * original under the timestamp scheme, generating its thumbnail eagerly. Returns the
   * <em>root-relative</em> path to persist on the receipt.
   *
   * @throws ReceiptFormatException if the bytes are not a JPEG/PNG or exceed the cap
   */
  public String storeOriginal(byte[] bytes) {
    if (bytes.length > MAX_BYTES) {
      throw new ReceiptFormatException(
          "That image is larger than the 15 MB limit — take or crop a smaller shot.");
    }
    String ext = ReceiptImageBytes.detectExtension(bytes);

    LocalDateTime now = LocalDateTime.now(clock);
    String yearMonth = now.format(YEAR_MONTH);
    String baseStem = now.format(STEM);
    String relOriginal = uniqueOriginalRelPath(yearMonth, baseStem, ext);

    Path originalPath = resolve(relOriginal);
    writeBytes(originalPath, bytes);
    // Eager thumbnail; self-heals later if the file goes missing.
    writeThumbnail(bytes, resolve(ReceiptPaths.thumbRelFor(relOriginal)));
    return relOriginal;
  }

  /**
   * Store the client-side-edited image (the pre-processed derivative the AI receives, 9c) under the
   * parallel {@code edited/} tree, keyed to the original's stem so the two line up by name, and
   * regenerate the thumbnail from it. Overwrites any existing edited file in place — a re-edit Save
   * replaces the previous derivative (receipt doc §6.1). The bytes are baked to JPEG by the browser
   * canvas; this validates that (magic bytes, size cap) so a mangled upload is our error, not a
   * later decode surprise. Returns the <em>root-relative</em> edited path to persist on the
   * receipt.
   *
   * @throws ReceiptFormatException if the bytes are not a JPEG within the size cap
   */
  public String storeEdited(String originalRelPath, byte[] editedBytes) {
    if (editedBytes.length > MAX_BYTES) {
      throw new ReceiptFormatException(
          "That edited image is larger than the 15 MB limit — crop or downscale further.");
    }
    if (!ReceiptImageBytes.isJpeg(editedBytes)) {
      throw new ReceiptFormatException("The edited image must be a JPEG.");
    }
    String relEdited = ReceiptPaths.editedRelFor(originalRelPath);
    writeBytes(resolve(relEdited), editedBytes);
    // The preview now reflects the edit: regenerate the thumbnail from the edited bytes.
    writeThumbnail(editedBytes, resolve(ReceiptPaths.thumbRelFor(originalRelPath)));
    return relEdited;
  }

  /**
   * Undo the pre-process edit (the *Discard edits* stage-undo, receipt doc §6.1): remove the edited
   * derivative and regenerate the thumbnail from the immutable original, so the preview reverts.
   * The original is never touched (ARCH-07). Missing files are ignored (idempotent).
   */
  public void discardEdited(String originalRelPath, String editedRelPath) {
    if (editedRelPath != null) {
      deleteIfPresent(resolve(editedRelPath));
    }
    writeThumbnail(
        readBytes(resolve(originalRelPath)), resolve(ReceiptPaths.thumbRelFor(originalRelPath)));
  }

  /** Read the raw bytes of a stored image by its root-relative path (original or edited). */
  public byte[] readImage(String relPath) {
    return readBytes(resolve(relPath));
  }

  /**
   * The thumbnail bytes for a receipt, self-healing: if the thumbnail file is missing it is
   * regenerated from the edited image when present, else the original, and stored before returning.
   *
   * @param originalRelPath the receipt's original path (the thumbnail stem is derived from it)
   * @param editedRelPath the edited derivative, or null — preferred as the thumbnail source
   */
  public byte[] readThumbnail(String originalRelPath, String editedRelPath) {
    Path thumbPath = resolve(ReceiptPaths.thumbRelFor(originalRelPath));
    if (!Files.exists(thumbPath)) {
      String sourceRel = editedRelPath != null ? editedRelPath : originalRelPath;
      writeThumbnail(readBytes(resolve(sourceRel)), thumbPath);
    }
    return readBytes(thumbPath);
  }

  /**
   * Remove a receipt's files — original, edited derivative (if any), and thumbnail. Missing files
   * are ignored (idempotent). Used by the delete ladder (§9b): removing a dead scan is allowed,
   * editing one in place is not.
   */
  public void deleteFiles(String originalRelPath, String editedRelPath) {
    deleteIfPresent(resolve(originalRelPath));
    deleteIfPresent(resolve(ReceiptPaths.thumbRelFor(originalRelPath)));
    if (editedRelPath != null) {
      deleteIfPresent(resolve(editedRelPath));
    }
  }

  // ── paths ────────────────────────────────────────────────────────────────────

  /**
   * The first free {@code originals/<yyyy>/<MM>/<stem>.<ext>}, suffixing {@code -2}, {@code -3},
   * and so on when a stem collides.
   */
  private String uniqueOriginalRelPath(String yearMonth, String baseStem, String ext) {
    int suffix = 1;
    String rel = ReceiptPaths.originalRel(yearMonth, baseStem, suffix, ext);
    while (Files.exists(resolve(rel))) {
      suffix = suffix < 2 ? 2 : suffix + 1;
      rel = ReceiptPaths.originalRel(yearMonth, baseStem, suffix, ext);
    }
    return rel;
  }

  /** Resolve a root-relative path against the storage root, refusing to escape it. */
  private Path resolve(String relPath) {
    Path resolved = root.resolve(relPath).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Path escapes the storage root: " + relPath);
    }
    return resolved;
  }

  // ── image / io ───────────────────────────────────────────────────────────────

  /**
   * Scale {@code source} down so its longest edge is at most {@link #THUMB_MAX_DIM}, write JPEG.
   */
  private static void writeThumbnail(byte[] source, Path dest) {
    try {
      BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
      if (decoded == null) {
        throw new ReceiptFormatException("That image could not be read as a JPEG or PNG.");
      }
      // Bake in the camera's EXIF orientation (ImageIO.read drops it) so the thumbnail is upright.
      BufferedImage original =
          ImageRotation.applyExifOrientation(decoded, ExifOrientation.of(source));
      int w = original.getWidth();
      int h = original.getHeight();
      double scale = Math.min(1.0, (double) THUMB_MAX_DIM / Math.max(w, h));
      int tw = Math.max(1, (int) Math.round(w * scale));
      int th = Math.max(1, (int) Math.round(h * scale));

      BufferedImage thumb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = thumb.createGraphics();
      try {
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, tw, th, null);
      } finally {
        g.dispose();
      }

      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      if (!ImageIO.write(thumb, THUMB_FORMAT, buffer)) {
        throw new IllegalStateException("No JPEG writer available for thumbnails");
      }
      writeBytes(dest, buffer.toByteArray());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to generate thumbnail at " + dest, e);
    }
  }

  private static void writeBytes(Path dest, byte[] bytes) {
    try {
      Path parent = dest.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(dest, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write " + dest, e);
    }
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + path, e);
    }
  }

  private static void deleteIfPresent(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete " + path, e);
    }
  }
}
