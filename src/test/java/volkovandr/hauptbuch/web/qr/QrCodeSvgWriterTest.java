package volkovandr.hauptbuch.web.qr;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.WriterException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Unit tier: the inline-SVG emitter behind the phone QR panel (issue landing-page/03).
 *
 * <p>The load-bearing test is {@link #roundTripsTheUrlThroughTheEmittedMarkup()}: it reads the
 * modules back <em>out of our own SVG string</em> and decodes them, which is the only thing that
 * proves the markup we ship says what we think it says. Asserting a rect count against the matrix
 * would only re-state the emitter's own loop.
 */
class QrCodeSvgWriterTest {

  private static final QrCodeSvgWriter WRITER = new QrCodeSvgWriter();
  private static final Pattern RECT =
      Pattern.compile("<rect x=\"(\\d+)\" y=\"(\\d+)\" width=\"(\\d+)\" height=\"1\"/>");
  private static final Pattern VIEW_BOX = Pattern.compile("viewBox=\"0 0 (\\d+) \\1\"");

  /** Each module is blown up to this many pixels so the binarizer has something to chew on. */
  private static final int SCALE = 3;

  /** The dark modules read back out of the emitted markup, as a {@code [y][x]} grid. */
  private static boolean[][] modulesOf(String svg) {
    Matcher viewBox = VIEW_BOX.matcher(svg);
    assertThat(viewBox.find()).as("viewBox is square and starts at the origin").isTrue();
    int size = Integer.parseInt(viewBox.group(1));

    boolean[][] modules = new boolean[size][size];
    Matcher rect = RECT.matcher(svg);
    while (rect.find()) {
      int x = Integer.parseInt(rect.group(1));
      int y = Integer.parseInt(rect.group(2));
      int run = Integer.parseInt(rect.group(3));
      for (int i = 0; i < run; i++) {
        modules[y][x + i] = true;
      }
    }
    return modules;
  }

  /** The minimum {@link LuminanceSource} over a square greyscale buffer. */
  private static final class PixelLuminanceSource extends LuminanceSource {
    private final byte[] pixels;

    PixelLuminanceSource(byte[] pixels, int side) {
      super(side, side);
      this.pixels = pixels.clone();
    }

    @Override
    public byte[] getRow(int y, byte[] row) {
      byte[] target = row != null && row.length >= getWidth() ? row : new byte[getWidth()];
      System.arraycopy(pixels, y * getWidth(), target, 0, getWidth());
      return target;
    }

    @Override
    public byte[] getMatrix() {
      return pixels.clone();
    }
  }

  /**
   * Reads the modules back out of {@code svg}, renders them as a greyscale image — dark modules
   * black, everything else white, each module blown up to {@link #SCALE} pixels so the binarizer
   * has something to chew on — and decodes that.
   */
  private static String decode(String svg) throws ReaderException {
    boolean[][] modules = modulesOf(svg);
    int side = modules.length * SCALE;
    byte[] pixels = new byte[side * side];
    for (int y = 0; y < side; y++) {
      for (int x = 0; x < side; x++) {
        pixels[y * side + x] = (byte) (modules[y / SCALE][x / SCALE] ? 0 : 0xff);
      }
    }
    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    hints.put(DecodeHintType.PURE_BARCODE, Boolean.TRUE);
    return new QRCodeReader()
        .decode(
            new BinaryBitmap(new HybridBinarizer(new PixelLuminanceSource(pixels, side))), hints)
        .getText();
  }

  @Test
  void roundTripsTheUrlThroughTheEmittedMarkup() throws WriterException, ReaderException {
    assertThat(decode(WRITER.svgFor("http://raspberrypi:8080/")))
        .isEqualTo("http://raspberrypi:8080/");
  }

  @Test
  void roundTripsGatewayUrlWithPathPrefix() throws WriterException, ReaderException {
    assertThat(decode(WRITER.svgFor("http://homenet/pi/hauptbuch/")))
        .isEqualTo("http://homenet/pi/hauptbuch/");
  }

  @Test
  void namesTheUrlInTheTitleForScreenReaders() throws WriterException {
    assertThat(WRITER.svgFor("http://raspberrypi:8080/"))
        .contains("<title>http://raspberrypi:8080/</title>")
        .contains("role=\"img\"");
  }

  @Test
  void escapesTheUrlInTheTitle() throws WriterException {
    String svg = WRITER.svgFor("http://homenet/?a=1&b=<2>");

    assertThat(svg).contains("<title>http://homenet/?a=1&amp;b=&lt;2&gt;</title>");
    assertThat(svg).doesNotContain("b=<2>");
  }

  @Test
  void leavesTheQuietZoneOfFourModulesOnEveryEdge() throws WriterException {
    boolean[][] modules = modulesOf(WRITER.svgFor("http://raspberrypi:8080/"));
    int size = modules.length;

    for (int i = 0; i < size; i++) {
      for (int edge = 0; edge < 4; edge++) {
        assertThat(modules[edge][i]).as("top row %d", edge).isFalse();
        assertThat(modules[size - 1 - edge][i]).as("bottom row %d", edge).isFalse();
        assertThat(modules[i][edge]).as("left column %d", edge).isFalse();
        assertThat(modules[i][size - 1 - edge]).as("right column %d", edge).isFalse();
      }
    }
  }

  @Test
  void paintsDarkModulesOnFullSizeLightBackgroundRegardlessOfTheme() throws WriterException {
    String svg = WRITER.svgFor("http://raspberrypi:8080/");
    Matcher viewBox = VIEW_BOX.matcher(svg);
    assertThat(viewBox.find()).isTrue();
    String size = viewBox.group(1);

    assertThat(svg)
        .contains("<rect width=\"" + size + "\" height=\"" + size + "\" fill=\"#ffffff\"/>")
        .contains("fill=\"#111111\"")
        .doesNotContain("currentColor")
        .doesNotContain("var(--");
  }
}
