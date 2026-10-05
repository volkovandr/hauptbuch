package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier: {@link CsvReader} splits text into rows and fields — quoting, embedded delimiters and
 * line breaks, both line endings, the byte-order mark, and rows with nothing in them.
 */
class CsvReaderTest {

  @Test
  void splitsRowsAndFieldsOnTheDelimiter() {
    assertThat(CsvReader.read("a;b;c\nd;e;f\n", ';', '"'))
        .containsExactly(List.of("a", "b", "c"), List.of("d", "e", "f"));
  }

  @Test
  void quotedFieldKeepsDelimitersLineBreaksAndDoubledQuotes() {
    assertThat(CsvReader.read("\"a;b\";\"line1\nline2\";\"say \"\"hi\"\"\"", ';', '"'))
        .containsExactly(List.of("a;b", "line1\nline2", "say \"hi\""));
  }

  @Test
  void readsWindowsLineEndingsAndDropsEmptyRows() {
    assertThat(CsvReader.read("a;b\r\n\r\n;\r\nc;d\r\n", ';', '"'))
        .containsExactly(List.of("a", "b"), List.of("c", "d"));
  }

  @Test
  void dropsLeadingByteOrderMark() {
    assertThat(CsvReader.read("﻿a,b\n", ',', '"')).containsExactly(List.of("a", "b"));
  }

  @Test
  void keepsEmptyFieldBetweenDelimiters() {
    assertThat(CsvReader.read("a;;c", ';', '"')).containsExactly(List.of("a", "", "c"));
  }
}
