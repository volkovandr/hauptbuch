package volkovandr.hauptbuch.importer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes a raw QIF file's bytes to text (import.md §4.4, slice a3). Money always writes
 * windows-1252 and replaces every character it cannot represent with a literal {@code ?}, but a
 * strict UTF-8 decode is tried first: UTF-8 and windows-1252 agree only on ASCII, and a valid
 * multi-byte UTF-8 sequence essentially never occurs by chance in cp1252 text, so a clean strict
 * decode is strong evidence the file really is UTF-8. Anything that fails the strict probe falls
 * back to windows-1252.
 *
 * <p>The detected encoding is a <em>proposal</em>: the upload preview (b2) shows it alongside the
 * first {@link #PREVIEW_LINE_COUNT} decoded lines so mojibake is visible before rows are staged,
 * and the owner can override it — {@link #decode(byte[], Encoding)} is that override path. The
 * choice is never silent.
 *
 * <p>A leading UTF-8 byte-order mark (U+FEFF) is stripped from the decoded text: Money never writes
 * one, but a file re-encoded by another tool routinely does, and everything downstream ({@link
 * QifRecordReader}, {@link QifDateFormat}) reads the first character of the first line literally.
 */
final class QifCharset {

  /** How many decoded lines the upload preview carries so mojibake is visible before staging. */
  static final int PREVIEW_LINE_COUNT = 50;

  private QifCharset() {}

  /** The two encodings a Money QIF export can be — and the preview's override choices. */
  enum Encoding {
    UTF_8,
    WINDOWS_1252;

    Charset charset() {
      return switch (this) {
        case UTF_8 -> StandardCharsets.UTF_8;
        case WINDOWS_1252 -> Charset.forName("windows-1252");
      };
    }
  }

  /**
   * A decoded file: the {@link Encoding} used and the full text.
   *
   * @param encoding the encoding this text was decoded with
   * @param text the fully decoded file
   */
  record Decoded(Encoding encoding, String text) {

    /** The head of the decoded text ({@link #PREVIEW_LINE_COUNT} lines) for the upload preview. */
    List<String> previewLines() {
      return previewLines(PREVIEW_LINE_COUNT);
    }

    /** The first {@code maxLines} lines of the decoded text, for the upload preview surface. */
    List<String> previewLines(int maxLines) {
      return text.lines().limit(maxLines).toList();
    }
  }

  /** Decode with a strict UTF-8 probe, falling back to windows-1252 when the probe fails (§4.4). */
  static Decoded decode(byte[] bytes) {
    try {
      return new Decoded(Encoding.UTF_8, stripByteOrderMark(strictUtf8(bytes)));
    } catch (CharacterCodingException notUtf8) {
      return decode(bytes, Encoding.WINDOWS_1252);
    }
  }

  /** Decode with a caller-chosen encoding — the upload preview's override path. */
  static Decoded decode(byte[] bytes, Encoding encoding) {
    return new Decoded(encoding, stripByteOrderMark(new String(bytes, encoding.charset())));
  }

  private static String stripByteOrderMark(String text) {
    return text.startsWith("\uFEFF") ? text.substring(1) : text;
  }

  private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
    CharsetDecoder strict =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    return strict.decode(ByteBuffer.wrap(bytes)).toString();
  }
}
