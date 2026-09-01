package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §a4): resolving a QIF {@code L}/{@code S} value into a target plus Money's
 * optional {@code /Class} tag suffix (import.md §4.2, §8).
 */
class QifTargetTest {

  @Test
  void resolvesAplainCategoryPath() {
    QifTarget.Resolved resolved = QifTarget.resolve("Vacation:car-rental");

    assertThat(resolved.target()).isEqualTo(new ImportedTarget.CategoryPath("Vacation:car-rental"));
    assertThat(resolved.className()).isNull();
  }

  @Test
  void resolvesAbracketedAccountReference() {
    QifTarget.Resolved resolved = QifTarget.resolve("[Bank24ru-EUR]");

    assertThat(resolved.target()).isEqualTo(new ImportedTarget.AccountReference("Bank24ru-EUR"));
    assertThat(resolved.className()).isNull();
  }

  @Test
  void splitsTheClassSuffixOffCategoryPath() {
    QifTarget.Resolved resolved = QifTarget.resolve("OtherIncome/holiday-fund");

    assertThat(resolved.target()).isEqualTo(new ImportedTarget.CategoryPath("OtherIncome"));
    assertThat(resolved.className()).isEqualTo("holiday-fund");
  }

  @Test
  void splitsTheClassSuffixOffAnAccountReference() {
    QifTarget.Resolved resolved = QifTarget.resolve("[Savings]/holiday-fund");

    assertThat(resolved.target()).isEqualTo(new ImportedTarget.AccountReference("Savings"));
    assertThat(resolved.className()).isEqualTo("holiday-fund");
  }

  @Test
  void dropsAdestroyedClassSuffix() {
    // §8: a "????" class collides with every other destroyed class, so it contributes no tag.
    QifTarget.Resolved resolved = QifTarget.resolve("OtherIncome/??????");

    assertThat(resolved.target()).isEqualTo(new ImportedTarget.CategoryPath("OtherIncome"));
    assertThat(resolved.className()).isNull();
  }
}
