package volkovandr.hauptbuch.statements;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits CSV text into rows of fields (RFC 4180 with a configurable delimiter and quote): a quoted
 * field may hold the delimiter, a line break, or a doubled quote. A leading byte-order mark is
 * dropped and rows with no content at all are skipped. Kept deliberately small — the statement
 * profile, not the reader, decides what the fields mean.
 */
final class CsvReader {

  private final String text;
  private final char delimiter;
  private final char quote;
  private int pos;

  private CsvReader(String text, char delimiter, char quote) {
    this.text = text.startsWith("﻿") ? text.substring(1) : text;
    this.delimiter = delimiter;
    this.quote = quote;
  }

  /** The rows of {@code text}, each a list of its fields in file order. */
  static List<List<String>> read(String text, char delimiter, char quote) {
    return new CsvReader(text, delimiter, quote).rows();
  }

  private List<List<String>> rows() {
    List<List<String>> rows = new ArrayList<>();
    while (pos < text.length()) {
      List<String> row = row();
      if (row.stream().anyMatch(value -> !value.isBlank())) {
        rows.add(row);
      }
    }
    return rows;
  }

  /** One row: fields up to the next unquoted line break (or the end), which is consumed. */
  private List<String> row() {
    List<String> row = new ArrayList<>();
    boolean more = true;
    while (more) {
      row.add(field());
      more = pos < text.length() && text.charAt(pos) == delimiter;
      if (more) {
        pos++;
      }
    }
    skipLineBreak();
    return row;
  }

  /** One field, stopping at the delimiter or line break that ends it (not consumed). */
  private String field() {
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    while (pos < text.length()) {
      char c = text.charAt(pos);
      if (quoted) {
        quoted = quotedChar(field);
      } else if (c == quote) {
        quoted = true;
        pos++;
      } else if (isFieldEnd(c)) {
        break;
      } else {
        field.append(c);
        pos++;
      }
    }
    return field.toString();
  }

  /** Consume one character inside quotes; false when it was the closing quote. */
  private boolean quotedChar(StringBuilder field) {
    char c = text.charAt(pos);
    pos++;
    if (c != quote) {
      field.append(c);
      return true;
    }
    if (pos < text.length() && text.charAt(pos) == quote) {
      field.append(quote);
      pos++;
      return true;
    }
    return false;
  }

  private boolean isFieldEnd(char c) {
    return c == delimiter || c == '\n' || c == '\r';
  }

  private void skipLineBreak() {
    if (pos < text.length() && text.charAt(pos) == '\r') {
      pos++;
    }
    if (pos < text.length() && text.charAt(pos) == '\n') {
      pos++;
    }
  }
}
