package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tier: {@link ExifOrientation} — the dependency-free EXIF orientation reader (§9b
 * thumbnail-orientation fix). Reading is checked against a hand-built minimal EXIF/TIFF byte block.
 */
class ExifOrientationTest {

  @Test
  void readsTheOrientationTagFromLittleEndianExif() {
    assertThat(ExifOrientation.of(jpegWithOrientation(6))).isEqualTo(6);
    assertThat(ExifOrientation.of(jpegWithOrientation(1))).isEqualTo(1);
    assertThat(ExifOrientation.of(jpegWithOrientation(8))).isEqualTo(8);
  }

  @Test
  void defaultsToOneForNonJpegBytes() {
    // PNG magic bytes — no EXIF orientation.
    byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    assertThat(ExifOrientation.of(png)).isEqualTo(1);
  }

  @Test
  void defaultsToOneForJpegWithoutExif() {
    // SOI immediately followed by start-of-scan: no APP1 segment.
    byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA};
    assertThat(ExifOrientation.of(jpeg)).isEqualTo(1);
  }

  /**
   * A minimal JPEG carrying only an EXIF APP1 segment with the Orientation tag set — enough for
   * {@link ExifOrientation#of} to parse. Little-endian ("II") TIFF, one IFD entry.
   */
  private static byte[] jpegWithOrientation(int orientation) {
    // TIFF: "II", magic 42, IFD0 at offset 8; one entry (Orientation SHORT), no next IFD.
    byte[] tiff = {
      0x49,
      0x49, // II (little-endian)
      0x2A,
      0x00, // magic 42
      0x08,
      0x00,
      0x00,
      0x00, // IFD0 offset = 8
      0x01,
      0x00, // entry count = 1
      0x12,
      0x01, // tag 0x0112 (Orientation)
      0x03,
      0x00, // type SHORT
      0x01,
      0x00,
      0x00,
      0x00, // count 1
      (byte) orientation,
      0x00,
      0x00,
      0x00, // value (SHORT in the low 2 bytes)
      0x00,
      0x00,
      0x00,
      0x00 // next IFD = 0
    };
    byte[] exifPrefix = {'E', 'x', 'i', 'f', 0, 0};
    int app1PayloadLen = exifPrefix.length + tiff.length;
    int app1SegmentLen = app1PayloadLen + 2; // length field counts itself

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(0xFF); // SOI
    out.write(0xD8);
    out.write(0xFF); // APP1
    out.write(0xE1);
    out.write((app1SegmentLen >> 8) & 0xFF); // length, big-endian
    out.write(app1SegmentLen & 0xFF);
    out.writeBytes(exifPrefix);
    out.writeBytes(tiff);
    return out.toByteArray();
  }
}
