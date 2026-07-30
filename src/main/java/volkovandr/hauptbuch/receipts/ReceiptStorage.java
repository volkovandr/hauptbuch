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

  private static final String ORIGINALS = "originals";
  private static final String THUMBS = "thumbs";
  private static final String THUMB_EXT = "jpg";
  private static final DateTimeFormatter STEM = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

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
    String ext = detectExtension(bytes);

    LocalDateTime now = LocalDateTime.now(clock);
    String yearMonth = now.format(DateTimeFormatter.ofPattern("yyyy/MM"));
    String baseStem = now.format(STEM);
    String relOriginal = uniqueOriginalRelPath(yearMonth, baseStem, ext);

    Path originalPath = resolve(relOriginal);
    writeBytes(originalPath, bytes);
    // Eager thumbnail; self-heals later if the file goes missing.
    writeThumbnail(bytes, resolve(thumbRelFor(relOriginal)));
    return relOriginal;
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
    Path thumbPath = resolve(thumbRelFor(originalRelPath));
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
    deleteIfPresent(resolve(thumbRelFor(originalRelPath)));
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
    String rel = ORIGINALS + "/" + yearMonth + "/" + baseStem + "." + ext;
    int suffix = 2;
    while (Files.exists(resolve(rel))) {
      rel = ORIGINALS + "/" + yearMonth + "/" + baseStem + "-" + suffix + "." + ext;
      suffix++;
    }
    return rel;
  }

  /**
   * The thumbnail path for an original: the {@code thumbs/} tree, same stem, always {@code .jpg}.
   */
  private static String thumbRelFor(String originalRelPath) {
    String tail = stripFirstSegment(originalRelPath);
    int dot = tail.lastIndexOf('.');
    String withoutExt = dot < 0 ? tail : tail.substring(0, dot);
    return THUMBS + "/" + withoutExt + "." + THUMB_EXT;
  }

  /** Everything after the first {@code originals/} (or {@code edited/}) path segment. */
  private static String stripFirstSegment(String relPath) {
    int slash = relPath.indexOf('/');
    return slash < 0 ? relPath : relPath.substring(slash + 1);
  }

  /** Resolve a root-relative path against the storage root, refusing to escape it. */
  private Path resolve(String relPath) {
    Path resolved = root.resolve(relPath).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Path escapes the storage root: " + relPath);
    }
    return resolved;
  }

  // ── magic bytes ──────────────────────────────────────────────────────────────

  /** Detect the image format by leading bytes; the file extension follows the detected type. */
  private static String detectExtension(byte[] bytes) {
    if (isJpeg(bytes)) {
      return "jpg";
    }
    if (isPng(bytes)) {
      return "png";
    }
    throw new ReceiptFormatException(
        "Only JPEG and PNG photos are accepted. PDF scans aren't supported yet.");
  }

  private static boolean isJpeg(byte[] b) {
    return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
  }

  private static boolean isPng(byte[] b) {
    return b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && (b[1] & 0xFF) == 0x50
        && (b[2] & 0xFF) == 0x4E
        && (b[3] & 0xFF) == 0x47
        && (b[4] & 0xFF) == 0x0D
        && (b[5] & 0xFF) == 0x0A
        && (b[6] & 0xFF) == 0x1A
        && (b[7] & 0xFF) == 0x0A;
  }

  // ── image / io ───────────────────────────────────────────────────────────────

  /**
   * Scale {@code source} down so its longest edge is at most {@link #THUMB_MAX_DIM}, write JPEG.
   */
  private static void writeThumbnail(byte[] source, Path dest) {
    try {
      BufferedImage original = ImageIO.read(new ByteArrayInputStream(source));
      if (original == null) {
        throw new ReceiptFormatException("That image could not be read as a JPEG or PNG.");
      }
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
      if (!ImageIO.write(thumb, THUMB_EXT, buffer)) {
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
