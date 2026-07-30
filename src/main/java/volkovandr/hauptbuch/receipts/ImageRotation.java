package volkovandr.hauptbuch.receipts;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Applies an EXIF orientation (as read by {@link ExifOrientation}) to a decoded image, baking the
 * rotation/flip into the pixels — so a thumbnail we re-encode is upright (§9b fix). Orientation 1
 * (or any out-of-range value) returns the image unchanged.
 */
final class ImageRotation {

  private static final int FLIP_HORIZONTAL = 2;
  private static final int ROTATE_180 = 3;
  private static final int FLIP_VERTICAL = 4;
  private static final int TRANSPOSE = 5;
  private static final int ROTATE_90_CW = 6;
  private static final int TRANSVERSE = 7;
  private static final int ROTATE_90_CCW = 8;

  private ImageRotation() {}

  /** {@code source} re-rendered with the given EXIF orientation baked in (1 = unchanged). */
  static BufferedImage applyExifOrientation(BufferedImage source, int orientation) {
    int w = source.getWidth();
    int h = source.getHeight();
    AffineTransform transform = transformFor(orientation, w, h);
    if (transform == null) {
      return source; // orientation 1 or out of range — nothing to do
    }
    // Orientations 5–8 rotate by 90°, so the output swaps width and height.
    boolean swap = orientation >= TRANSPOSE;
    BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = dst.createGraphics();
    try {
      g.drawImage(source, transform, null);
    } finally {
      g.dispose();
    }
    return dst;
  }

  /**
   * The source→destination transform for an EXIF orientation, or null when none is needed. Matrix
   * args are {@code (m00, m10, m01, m11, m02, m12)} — the standard EXIF orientation transforms.
   */
  private static AffineTransform transformFor(int orientation, int w, int h) {
    return switch (orientation) {
      case FLIP_HORIZONTAL -> new AffineTransform(-1, 0, 0, 1, w, 0);
      case ROTATE_180 -> new AffineTransform(-1, 0, 0, -1, w, h);
      case FLIP_VERTICAL -> new AffineTransform(1, 0, 0, -1, 0, h);
      case TRANSPOSE -> new AffineTransform(0, 1, 1, 0, 0, 0);
      case ROTATE_90_CW -> new AffineTransform(0, 1, -1, 0, h, 0);
      case TRANSVERSE -> new AffineTransform(0, -1, -1, 0, h, w);
      case ROTATE_90_CCW -> new AffineTransform(0, -1, 1, 0, 0, w);
      default -> null;
    };
  }
}
