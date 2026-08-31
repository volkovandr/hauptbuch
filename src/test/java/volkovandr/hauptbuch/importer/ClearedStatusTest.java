package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tier (plan §a2): the QIF {@code C} field classification (import.md §4.2). */
class ClearedStatusTest {

  @Test
  void classifiesClearedCodes() {
    assertThat(ClearedStatus.fromCode("*")).isEqualTo(ClearedStatus.CLEARED);
    assertThat(ClearedStatus.fromCode("c")).isEqualTo(ClearedStatus.CLEARED);
  }

  @Test
  void classifiesReconciledCodes() {
    assertThat(ClearedStatus.fromCode("X")).isEqualTo(ClearedStatus.RECONCILED);
    assertThat(ClearedStatus.fromCode("R")).isEqualTo(ClearedStatus.RECONCILED);
  }

  @Test
  void defaultsToUnreconciledWhenAbsent() {
    assertThat(ClearedStatus.fromCode(null)).isEqualTo(ClearedStatus.UNRECONCILED);
  }

  @Test
  void rejectsUnknownCode() {
    assertThatThrownBy(() -> ClearedStatus.fromCode("?")).isInstanceOf(QifRejectedException.class);
  }
}
