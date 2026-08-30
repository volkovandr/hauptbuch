package volkovandr.hauptbuch.web.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Encodes a URL as a QR code and emits it as an inline {@code <svg>} (tech-stack §4.5).
 *
 * <p>The geometry is in <em>module units</em> — the {@code viewBox} is the matrix size and every
 * rect is one module tall — so the rendered size is a single CSS {@code width} on the element and
 * the markup carries no pixel dimensions at all.
 *
 * <p>Colours are fixed dark-on-light rather than theme tokens: an inverted QR is not universally
 * readable, and scanner reliability beats matching the page's dark mode.
 */
@Component
public class QrCodeSvgWriter {

  /** Error-correction level M — the usual balance for a short URL on a bright screen. */
  private static final ErrorCorrectionLevel ERROR_CORRECTION = ErrorCorrectionLevel.M;

  /** The quiet zone the QR spec requires, in modules. */
  private static final int QUIET_ZONE = 4;

  private static final String LIGHT = "#ffffff";
  private static final String DARK = "#111111";

  /** Roughly the markup a typical short-URL code needs; the builder grows if it is wrong. */
  private static final int EXPECTED_SVG_LENGTH = 4096;

  /**
   * The inline SVG for {@code url}.
   *
   * @throws WriterException if the URL cannot be encoded — in practice only if it is too long for
   *     any QR version at this error-correction level
   */
  public String svgFor(String url) throws WriterException {
    Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
    hints.put(EncodeHintType.ERROR_CORRECTION, ERROR_CORRECTION);
    hints.put(EncodeHintType.MARGIN, QUIET_ZONE);
    hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
    // Width and height of 0 ask for the natural size: one module per unit, quiet zone included.
    BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints);
    return render(matrix, url);
  }

  private static String render(BitMatrix matrix, String url) {
    int size = matrix.getWidth();
    StringBuilder svg =
        new StringBuilder(EXPECTED_SVG_LENGTH)
            .append(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\""
                    + " shape-rendering=\"crispEdges\" viewBox=\"0 0 ")
            .append(size)
            .append(' ')
            .append(size)
            .append("\"><title>")
            // The only interpolated value in this markup, and it is inlined with th:utext.
            .append(HtmlUtils.htmlEscape(url))
            .append("</title><rect width=\"")
            .append(size)
            .append("\" height=\"")
            .append(size)
            .append("\" fill=\"" + LIGHT + "\"/><g fill=\"" + DARK + "\">");
    appendModules(svg, matrix, size);
    return svg.append("</g></svg>").toString();
  }

  /**
   * One rect per <em>horizontal run</em> of dark modules rather than per module. Same picture, a
   * fraction of the markup — a QR is mostly short runs, and this page is rendered on every visit.
   */
  private static void appendModules(StringBuilder svg, BitMatrix matrix, int size) {
    for (int y = 0; y < size; y++) {
      int x = 0;
      while (x < size) {
        if (!matrix.get(x, y)) {
          x++;
          continue;
        }
        int run = 0;
        while (x + run < size && matrix.get(x + run, y)) {
          run++;
        }
        svg.append("<rect x=\"")
            .append(x)
            .append("\" y=\"")
            .append(y)
            .append("\" width=\"")
            .append(run)
            .append("\" height=\"1\"/>");
        x += run;
      }
    }
  }
}
