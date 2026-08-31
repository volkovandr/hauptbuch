package volkovandr.hauptbuch.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an already-decoded QIF file's text into its {@code !Type:} header line and its {@code
 * ^}-terminated records (import.md §4.1) — the record reader a2 names. Each record comes back as
 * its raw field-lines, blank lines and the {@code ^} terminator stripped; field semantics (which
 * letter means what) are {@link QifParser}'s job, not this one's — this class knows nothing about
 * {@code D}/{@code T}/{@code L} etc.
 *
 * <p>{@link String#lines()} splits on {@code \n}, {@code \r\n}, or a bare {@code \r} alike, so
 * Money's CRLF export needs no special handling here.
 */
final class QifRecordReader {

  private static final String HEADER_PREFIX = "!";
  private static final String RECORD_TERMINATOR = "^";

  private QifRecordReader() {}

  /** One file's header line (verbatim) and its parsed records, each a list of raw field-lines. */
  record Result(String header, List<List<String>> records) {}

  static Result read(String text) {
    List<String> lines = text.lines().filter(line -> !line.isBlank()).toList();
    if (lines.isEmpty() || !lines.get(0).startsWith(HEADER_PREFIX)) {
      throw new QifRejectedException("This file has no QIF !Type: header.");
    }
    String header = lines.get(0);
    List<List<String>> records = new ArrayList<>();
    List<String> current = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      if (RECORD_TERMINATOR.equals(line)) {
        records.add(List.copyOf(current));
        current.clear();
      } else {
        current.add(line);
      }
    }
    if (!current.isEmpty()) {
      throw new QifRejectedException("This file's last record has no ^ terminator.");
    }
    return new Result(header, List.copyOf(records));
  }
}
