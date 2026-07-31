package volkovandr.hauptbuch.receipts;

/**
 * Image-format detection by magic bytes for the receipt store: JPEG and PNG only (PDF scans are a
 * backlog need, §9b). Kept apart from {@link ReceiptStorage} so "what kind of image is this?" is
 * one small, cohesive concern rather than more surface on the storage class.
 */
final class ReceiptImageBytes {

  private ReceiptImageBytes() {}

  /**
   * The file extension implied by the leading bytes ({@code jpg} / {@code png}).
   *
   * @throws ReceiptFormatException if the bytes are neither a JPEG nor a PNG
   */
  static String detectExtension(byte[] bytes) {
    if (isJpeg(bytes)) {
      return "jpg";
    }
    if (isPng(bytes)) {
      return "png";
    }
    throw new ReceiptFormatException(
        "Only JPEG and PNG photos are accepted. PDF scans aren't supported yet.");
  }

  /** Whether the bytes start with the JPEG SOI marker — the edited bake is always a JPEG. */
  static boolean isJpeg(byte[] b) {
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
}
