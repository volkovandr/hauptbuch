package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §a2): the {@code ^}-terminated record reader — pure text splitting, no field
 * semantics (import.md §4.1).
 */
class QifRecordReaderTest {

  @Test
  void splitsHeaderAndRecords() {
    String text =
        "!Type:Bank\r\nD01/01'2020\r\nT10.00\r\nLFood\r\n^\r\n"
            + "D02/01'2020\r\nT-5.00\r\nLFuel\r\n^\r\n";

    QifRecordReader.Result result = QifRecordReader.read(text);

    assertThat(result.header()).isEqualTo("!Type:Bank");
    assertThat(result.records())
        .containsExactly(
            List.of("D01/01'2020", "T10.00", "LFood"), List.of("D02/01'2020", "T-5.00", "LFuel"));
  }

  @Test
  void toleratesBlankLinesBetweenRecords() {
    String text = "!Type:Bank\n\nD01/01'2020\nT10.00\nLFood\n\n^\n\n";

    QifRecordReader.Result result = QifRecordReader.read(text);

    assertThat(result.records()).containsExactly(List.of("D01/01'2020", "T10.00", "LFood"));
  }

  @Test
  void rejectsFileWithNoHeader() {
    assertThatThrownBy(() -> QifRecordReader.read("D01/01'2020\nT10.00\n^\n"))
        .isInstanceOf(QifRejectedException.class);
  }

  @Test
  void rejectsTrailingRecordWithNoTerminator() {
    assertThatThrownBy(() -> QifRecordReader.read("!Type:Bank\nD01/01'2020\nT10.00\nLFood\n"))
        .isInstanceOf(QifRejectedException.class);
  }
}
