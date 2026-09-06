package volkovandr.hauptbuch.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an already-decoded QIF file's text into its {@code !Type:} header line and its {@code
 * ^}-terminated records (import.md §4.1) — the record reader a2 names. Each record comes back as
 * its raw field-lines (blank lines and the {@code ^} terminator stripped) together with the 1-based
 * source line its first field sits on, so {@link QifParser} can name that line when it rejects a
 * record. Field semantics (which letter means what) are {@link QifParser}'s job, not this one's —
 * this class knows nothing about {@code D}/{@code T}/{@code L} etc.
 *
 * <p>{@link String#lines()} splits on {@code \n}, {@code \r\n}, or a bare {@code \r} alike, so
 * Money's CRLF export needs no special handling here.
 */
final class QifRecordReader {

  private static final String HEADER_PREFIX = "!";
  private static final String RECORD_TERMINATOR = "^";

  private QifRecordReader() {}

  /** One file's header line (verbatim) and its parsed records, in file order. */
  record Result(String header, List<SourceRecord> records) {}

  /**
   * One {@code ^}-terminated record: its raw field-lines and the 1-based line the first sits on.
   */
  record SourceRecord(int firstLine, List<String> fieldLines) {}

  static Result read(String text) {
    List<String> rawLines = text.lines().toList();
    int headerLine = indexOfHeader(rawLines);
    Accumulator accumulator = new Accumulator();
    for (int lineNumber = headerLine + 2; lineNumber <= rawLines.size(); lineNumber++) {
      accumulator.accept(rawLines.get(lineNumber - 1), lineNumber);
    }
    return new Result(rawLines.get(headerLine), accumulator.finish());
  }

  /**
   * The 0-based index of the {@code !Type:} header — the first non-blank line, which must be it.
   */
  private static int indexOfHeader(List<String> rawLines) {
    int index = 0;
    while (index < rawLines.size() && rawLines.get(index).isBlank()) {
      index++;
    }
    if (index < rawLines.size() && rawLines.get(index).startsWith(HEADER_PREFIX)) {
      return index;
    }
    throw new QifRejectedException("This file has no QIF !Type: header.");
  }

  /** Folds the post-header lines into {@code ^}-terminated records, tracking each one's start. */
  private static final class Accumulator {
    private final List<SourceRecord> records = new ArrayList<>();
    private final List<String> current = new ArrayList<>();
    private int firstLine;

    private void accept(String line, int lineNumber) {
      if (line.isBlank()) {
        return;
      }
      if (RECORD_TERMINATOR.equals(line)) {
        records.add(new SourceRecord(firstLine, List.copyOf(current)));
        current.clear();
        return;
      }
      if (current.isEmpty()) {
        firstLine = lineNumber;
      }
      current.add(line);
    }

    private List<SourceRecord> finish() {
      if (!current.isEmpty()) {
        throw new QifRejectedException(
            "This file's last record (from line " + firstLine + ") has no ^ terminator.");
      }
      return List.copyOf(records);
    }
  }
}
