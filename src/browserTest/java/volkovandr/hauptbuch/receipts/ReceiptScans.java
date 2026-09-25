package volkovandr.hauptbuch.receipts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/** Generated receipt scans for the browser tests: real JPEG bytes of a chosen size and colour. */
final class ReceiptScans {

  private ReceiptScans() {}

  /** A solid {@code color} JPEG of {@code width} × {@code height} pixels. */
  static byte[] jpeg(int width, int height, Color color) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(color);
    graphics.fillRect(0, 0, width, height);
    graphics.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "jpg", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** A small white scan — for tests that only need a receipt to exist. */
  static byte[] small() {
    return jpeg(120, 160, Color.WHITE);
  }

  /** Decode JPEG bytes. */
  static BufferedImage read(byte[] bytes) {
    try {
      return ImageIO.read(new ByteArrayInputStream(bytes));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
