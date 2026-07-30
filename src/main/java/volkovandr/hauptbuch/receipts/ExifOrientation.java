package volkovandr.hauptbuch.receipts;

/**
 * Reads a JPEG's EXIF orientation tag (§9b thumbnail-orientation fix). Cameras commonly store
 * landscape sensor pixels plus an orientation tag meaning "rotate to portrait"; browsers honour the
 * tag when displaying the original, but {@code ImageIO.read} returns the raw pixels and drops it —
 * so a re-encoded thumbnail would appear rotated unless we read the tag and apply it ({@link
 * ImageRotation}).
 *
 * <p>Deliberately dependency-free: a small, bounds-checked scan of the JPEG APP1 / EXIF / TIFF /
 * IFD structure for the single Orientation tag (0x0112), rather than pulling in a metadata library.
 * Anything without a readable tag (PNGs, truncated or non-EXIF JPEGs) reads as {@link #NORMAL}.
 */
final class ExifOrientation {

  /** Orientation 1 — the image is already upright; also the "no readable tag" answer. */
  static final int NORMAL = 1;

  private static final int MIN_ORIENTATION = 1;
  private static final int MAX_ORIENTATION = 8;

  // JPEG markers.
  private static final int MARKER = 0xFF;
  private static final int START_OF_IMAGE = 0xD8;
  private static final int END_OF_IMAGE = 0xD9;
  private static final int START_OF_SCAN = 0xDA;
  private static final int APP1 = 0xE1;

  // TIFF byte-order marks ("II" little-endian / "MM" big-endian) and structure sizes.
  private static final int INTEL = 0x49;
  private static final int MOTOROLA = 0x4D;
  private static final int MIN_HEADER = 2;
  private static final int MARKER_AND_LENGTH = 4;
  private static final int MIN_SEGMENT = 2;
  private static final int TIFF_HEADER = 8;
  private static final int IFD_COUNT_SIZE = 2;
  private static final int IFD_ENTRY_SIZE = 12;
  private static final int VALUE_FIELD_OFFSET = 8;
  private static final int ORIENTATION_TAG = 0x0112;
  private static final byte[] EXIF_PREFIX = {'E', 'x', 'i', 'f', 0, 0};

  private ExifOrientation() {}

  /**
   * The EXIF orientation (1–8) of a JPEG, or {@link #NORMAL} when absent/unreadable/not a JPEG. 2–4
   * are flips/180°; 5–8 involve a 90° rotation (so swap width and height).
   */
  static int of(byte[] bytes) {
    if (!isJpeg(bytes)) {
      return NORMAL;
    }
    int offset = MIN_HEADER;
    while (offset + MARKER_AND_LENGTH <= bytes.length) {
      int segmentEnd = segmentEnd(bytes, offset);
      if (segmentEnd < 0) {
        break; // stray byte or a terminal marker — no more segments
      }
      if (byteAt(bytes, offset + 1) == APP1) {
        int found = fromApp1(bytes, offset + MARKER_AND_LENGTH, segmentEnd);
        if (found != NORMAL) {
          return found;
        }
      }
      offset = segmentEnd;
    }
    return NORMAL;
  }

  /** End index of the marker segment at {@code offset}, or -1 to stop scanning. */
  private static int segmentEnd(byte[] b, int offset) {
    if (byteAt(b, offset) != MARKER) {
      return -1; // not aligned on a marker
    }
    int marker = byteAt(b, offset + 1);
    if (marker == END_OF_IMAGE || marker == START_OF_SCAN) {
      return -1; // no further metadata segments
    }
    int length = u16Big(b, offset + 2);
    int end = offset + MIN_SEGMENT + length;
    return length >= MIN_SEGMENT && end <= b.length ? end : -1;
  }

  private static boolean isJpeg(byte[] b) {
    return b.length >= MIN_HEADER && byteAt(b, 0) == MARKER && byteAt(b, 1) == START_OF_IMAGE;
  }

  /** Orientation from an APP1 segment's EXIF/TIFF payload, or {@link #NORMAL} if not present. */
  private static int fromApp1(byte[] b, int start, int end) {
    if (!hasExifPrefix(b, start, end)) {
      return NORMAL;
    }
    int tiff = start + EXIF_PREFIX.length;
    if (tiff + TIFF_HEADER > end) {
      return NORMAL;
    }
    int order0 = byteAt(b, tiff);
    int order1 = byteAt(b, tiff + 1);
    boolean intel = order0 == INTEL && order1 == INTEL;
    boolean motorola = order0 == MOTOROLA && order1 == MOTOROLA;
    if (!intel && !motorola) {
      return NORMAL; // not a valid TIFF byte-order mark
    }
    int ifd = tiff + u32(b, tiff + MARKER_AND_LENGTH, intel);
    if (ifd + IFD_COUNT_SIZE > end) {
      return NORMAL;
    }
    return orientationInIfd(b, ifd, end, intel);
  }

  /** Scan IFD0's entries for the Orientation tag, returning its value or {@link #NORMAL}. */
  private static int orientationInIfd(byte[] b, int ifd, int end, boolean little) {
    int entries = u16(b, ifd, little);
    int p = ifd + IFD_COUNT_SIZE;
    for (int i = 0; i < entries && p + IFD_ENTRY_SIZE <= end; i++, p += IFD_ENTRY_SIZE) {
      if (u16(b, p, little) == ORIENTATION_TAG) {
        int value = u16(b, p + VALUE_FIELD_OFFSET, little); // SHORT sits in the value/offset field
        return value >= MIN_ORIENTATION && value <= MAX_ORIENTATION ? value : NORMAL;
      }
    }
    return NORMAL;
  }

  private static boolean hasExifPrefix(byte[] b, int start, int end) {
    if (start + EXIF_PREFIX.length > end) {
      return false;
    }
    for (int i = 0; i < EXIF_PREFIX.length; i++) {
      if (b[start + i] != EXIF_PREFIX[i]) {
        return false;
      }
    }
    return true;
  }

  private static int u16(byte[] b, int i, boolean little) {
    return little ? byteAt(b, i) | (byteAt(b, i + 1) << 8) : (byteAt(b, i) << 8) | byteAt(b, i + 1);
  }

  private static int u16Big(byte[] b, int i) {
    return (byteAt(b, i) << 8) | byteAt(b, i + 1);
  }

  private static int u32(byte[] b, int i, boolean little) {
    return little
        ? byteAt(b, i)
            | (byteAt(b, i + 1) << 8)
            | (byteAt(b, i + 2) << 16)
            | (byteAt(b, i + 3) << 24)
        : (byteAt(b, i) << 24)
            | (byteAt(b, i + 1) << 16)
            | (byteAt(b, i + 2) << 8)
            | byteAt(b, i + 3);
  }

  private static int byteAt(byte[] b, int i) {
    return b[i] & 0xFF;
  }
}
