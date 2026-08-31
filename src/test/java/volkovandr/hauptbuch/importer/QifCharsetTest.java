package volkovandr.hauptbuch.importer;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §a3): the strict-UTF-8-probe / windows-1252-fallback decode of a raw QIF file's
 * bytes (import.md §4.4). UTF-8 and windows-1252 agree only on ASCII, so the probe is a real
 * choice; the fixtures are synthetic bytes reproducing shapes a real export proved (import.md §14).
 */
class QifCharsetTest {

  @Test
  void decodesValidUtf8AsUtf8() {
    byte[] bytes = "Pcafé".getBytes(UTF_8);

    QifCharset.Decoded decoded = QifCharset.decode(bytes);

    assertThat(decoded.encoding()).isEqualTo(QifCharset.Encoding.UTF_8);
    assertThat(decoded.text()).isEqualTo("Pcafé");
  }

  @Test
  void fallsBackToWindows1252WhenBytesAreInvalidUtf8() throws IOException {
    // 0x92 is a bare UTF-8 continuation byte (malformed on its own); in windows-1252 it is U+2019.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write("PM".getBytes(US_ASCII));
    out.write(0x92);
    out.write("rs".getBytes(US_ASCII));

    QifCharset.Decoded decoded = QifCharset.decode(out.toByteArray());

    assertThat(decoded.encoding()).isEqualTo(QifCharset.Encoding.WINDOWS_1252);
    assertThat(decoded.text()).isEqualTo("PM’rs");
  }

  @Test
  void stripsLeadingUtf8ByteOrderMark() {
    byte[] bytes = {
      (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '!', 'T', 'y', 'p', 'e', ':', 'B', 'a', 'n', 'k'
    };

    QifCharset.Decoded decoded = QifCharset.decode(bytes);

    assertThat(decoded.encoding()).isEqualTo(QifCharset.Encoding.UTF_8);
    assertThat(decoded.text()).isEqualTo("!Type:Bank");
  }

  @Test
  void asciiOnlyInputDecodesIdenticallyEitherWay() {
    byte[] bytes = "!Type:Bank\nD01/01'2020\nT-2.00\nLFood\n^\n".getBytes(US_ASCII);

    QifCharset.Decoded auto = QifCharset.decode(bytes);
    QifCharset.Decoded forced = QifCharset.decode(bytes, QifCharset.Encoding.WINDOWS_1252);

    assertThat(auto.encoding()).isEqualTo(QifCharset.Encoding.UTF_8);
    assertThat(auto.text()).isEqualTo(forced.text());
    assertThat(auto.text()).isEqualTo("!Type:Bank\nD01/01'2020\nT-2.00\nLFood\n^\n");
  }

  @Test
  void overrideDecodesWithTheChosenEncoding() {
    byte[] bytes = {(byte) 0xE9}; // é in windows-1252; a truncated lead byte in UTF-8

    QifCharset.Decoded decoded = QifCharset.decode(bytes, QifCharset.Encoding.WINDOWS_1252);

    assertThat(decoded.encoding()).isEqualTo(QifCharset.Encoding.WINDOWS_1252);
    assertThat(decoded.text()).isEqualTo("é");
  }

  @Test
  void previewLinesCarriesOnlyTheHeadOfTheText() {
    QifCharset.Decoded decoded = QifCharset.decode("line1\nline2\nline3\nline4".getBytes(US_ASCII));

    assertThat(decoded.previewLines(2)).containsExactly("line1", "line2");
  }

  @Test
  void previewLinesDefaultsToThePreviewLineCount() {
    QifCharset.Decoded decoded = QifCharset.decode("line1\nline2\nline3\nline4".getBytes(US_ASCII));

    assertThat(decoded.previewLines())
        .hasSize(4)
        .isEqualTo(decoded.previewLines(QifCharset.PREVIEW_LINE_COUNT));
  }
}
