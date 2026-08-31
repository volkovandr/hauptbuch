package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §a2): the shared amount routine every {@code T}/{@code U}/{@code $} field goes
 * through, including the thousands-grouping comma import.md v0.2 confirmed against a real export.
 */
class QifAmountsTest {

  @Test
  void parsesPlainAmount() {
    assertThat(QifAmounts.parse("-161.07")).isEqualByComparingTo("-161.07");
  }

  @Test
  void stripsThousandsGroupingCommas() {
    assertThat(QifAmounts.parse("-650,000.00")).isEqualByComparingTo("-650000.00");
    assertThat(QifAmounts.parse("11,707.31")).isEqualByComparingTo("11707.31");
  }

  @Test
  void rejectsBlankAmount() {
    assertThatThrownBy(() -> QifAmounts.parse(" ")).isInstanceOf(QifRejectedException.class);
  }

  @Test
  void rejectsUnparsableAmount() {
    assertThatThrownBy(() -> QifAmounts.parse("not-a-number"))
        .isInstanceOf(QifRejectedException.class);
  }
}
