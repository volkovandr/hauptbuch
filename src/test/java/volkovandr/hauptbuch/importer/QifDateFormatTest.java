package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §a3): whole-file day/month-order detection with evidence (import.md §4.3). A
 * first component past 12 proves {@code DD/MM}; a second component past 12 proves {@code MM/DD}; a
 * file where neither ever happens is genuinely ambiguous. The order is a proposal the upload
 * preview shows and the owner confirms — a silent day/month swap corrupts every date in the
 * campaign.
 */
class QifDateFormatTest {

  @Test
  void detectsDayMonthFromFirstComponentPastTwelve() {
    String text =
        """
        !Type:Bank
        D07/03'2005
        T-2.00
        LFood
        ^
        D31/03'2007
        T-3.00
        LFood
        ^
        """;

    QifDateFormat.Detection detection = QifDateFormat.detect(text);

    assertThat(detection.order()).isEqualTo(QifDateFormat.Order.DAY_MONTH);
    assertThat(detection.evidenceLine()).isEqualTo("D31/03'2007");
    assertThat(detection.evidenceLineNumber()).isEqualTo(6);
    assertThat(detection.describe()).isEqualTo("DD/MM, proven by `D31/03'2007` on line 6");
  }

  @Test
  void detectsMonthDayFromSecondComponentPastTwelve() {
    String text =
        """
        !Type:Bank
        D03/31'2007
        T-3.00
        LFood
        ^
        """;

    QifDateFormat.Detection detection = QifDateFormat.detect(text);

    assertThat(detection.order()).isEqualTo(QifDateFormat.Order.MONTH_DAY);
    assertThat(detection.evidenceLine()).isEqualTo("D03/31'2007");
    assertThat(detection.evidenceLineNumber()).isEqualTo(2);
    assertThat(detection.describe()).isEqualTo("MM/DD, proven by `D03/31'2007` on line 2");
  }

  @Test
  void reportsAmbiguousWhenNoDateDistinguishesTheOrder() {
    String text =
        """
        !Type:Bank
        D07/03'2005
        T-2.00
        LFood
        ^
        D01/12'2006
        T-3.00
        LFood
        ^
        """;

    QifDateFormat.Detection detection = QifDateFormat.detect(text);

    assertThat(detection.order()).isEqualTo(QifDateFormat.Order.AMBIGUOUS);
    assertThat(detection.evidenceLine()).isNull();
    assertThat(detection.evidenceLineNumber()).isNull();
    assertThat(detection.describe())
        .isEqualTo("AMBIGUOUS — no date in this file distinguishes them");
  }

  @Test
  void acceptsApostropheDotAndDashSeparators() {
    String text = "!Type:Bank\nD31.03.2007\nT-3.00\nLFood\n^\n";

    assertThat(QifDateFormat.detect(text).order()).isEqualTo(QifDateFormat.Order.DAY_MONTH);
  }

  @Test
  void rejectsFileWhoseDatesContradictEachOther() {
    String text =
        """
        !Type:Bank
        D31/01'2020
        T-2.00
        LFood
        ^
        D01/31'2020
        T-3.00
        LFood
        ^
        """;

    assertThatThrownBy(() -> QifDateFormat.detect(text))
        .isInstanceOf(QifRejectedException.class)
        .hasMessageContaining("contradict");
  }

  @Test
  void rejectsUnparseableDateLine() {
    String text = "!Type:Bank\nDnot-a-date\nT-3.00\nLFood\n^\n";

    assertThatThrownBy(() -> QifDateFormat.detect(text)).isInstanceOf(QifRejectedException.class);
  }

  @Test
  void rejectsDateComponentOutsideOneToThirtyOne() {
    String text = "!Type:Bank\nD00/03'2007\nT-3.00\nLFood\n^\n";

    assertThatThrownBy(() -> QifDateFormat.detect(text)).isInstanceOf(QifRejectedException.class);
  }

  @Test
  void parsesRawValueWithTheConfirmedDayMonthOrder() {
    assertThat(QifDateFormat.toLocalDate("26/11'2011", QifDateFormat.Order.DAY_MONTH))
        .isEqualTo(LocalDate.of(2011, 11, 26));
  }

  @Test
  void parsesRawValueWithTheConfirmedMonthDayOrder() {
    assertThat(QifDateFormat.toLocalDate("11/26'2011", QifDateFormat.Order.MONTH_DAY))
        .isEqualTo(LocalDate.of(2011, 11, 26));
  }

  @Test
  void readsTwoDigitYearsByMoneySeparatorConvention() {
    assertThat(QifDateFormat.toLocalDate("03/01'00", QifDateFormat.Order.DAY_MONTH))
        .isEqualTo(LocalDate.of(2000, 1, 3));
    assertThat(QifDateFormat.toLocalDate("03/01/98", QifDateFormat.Order.DAY_MONTH))
        .isEqualTo(LocalDate.of(1998, 1, 3));
  }

  @Test
  void refusesToParseWhileTheOrderIsAmbiguous() {
    assertThatThrownBy(() -> QifDateFormat.toLocalDate("03/01'2005", QifDateFormat.Order.AMBIGUOUS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AMBIGUOUS");
  }

  @Test
  void rejectsUnparseableRawValue() {
    assertThatThrownBy(() -> QifDateFormat.toLocalDate("not-a-date", QifDateFormat.Order.DAY_MONTH))
        .isInstanceOf(QifRejectedException.class);
  }

  @Test
  void rejectsRawValueOutsideTheRealCalendar() {
    assertThatThrownBy(() -> QifDateFormat.toLocalDate("31/02'2011", QifDateFormat.Order.DAY_MONTH))
        .isInstanceOf(QifRejectedException.class);
  }
}
