package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §a4): the {@code ?}-destruction predicate (import.md §4.4) shared by payee, class
 * and account-name handling.
 */
class QifTextTest {

  @Test
  void treatsAnEntirelyQuestionMarkStringAsDestroyed() {
    assertThat(QifText.isDestroyed("????")).isTrue();
    assertThat(QifText.isDestroyed("???? ?????????")).isTrue();
  }

  @Test
  void treatsApartiallyReadableStringAsNotDestroyed() {
    assertThat(QifText.isDestroyed("M?rs")).isFalse();
    assertThat(QifText.isDestroyed("???????? Rewe")).isFalse();
  }

  @Test
  void treatsTheEmptyStringAsNotDestroyed() {
    assertThat(QifText.isDestroyed("")).isFalse();
  }

  @Test
  void blankToNullNullsNullAndBlankOnly() {
    assertThat(QifText.blankToNull(null)).isNull();
    assertThat(QifText.blankToNull("  ")).isNull();
    assertThat(QifText.blankToNull("lunch")).isEqualTo("lunch");
  }
}
