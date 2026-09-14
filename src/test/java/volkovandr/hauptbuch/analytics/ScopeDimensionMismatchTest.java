package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): the "scope misses the dimension" message (reporting.md §6.1) —
 * decidable from a resolved {@link Dimension} and {@link Scope} alone, no DB needed.
 */
class ScopeDimensionMismatchTest {

  @Test
  void namesCategoryAndTheAssetLiabilityScopeItMisses() {
    Scope scope = Scope.ofTypes("asset", "liability");

    assertThat(ScopeDimensionMismatch.check(Dimension.CATEGORY, scope))
        .isEqualTo("Category covers income and expense accounts; neither is in scope.");
  }

  @Test
  void namesAccountAndTheIncomeExpenseScopeItMisses() {
    Scope scope = Scope.ofTypes("income", "expense");

    assertThat(ScopeDimensionMismatch.check(Dimension.ACCOUNT, scope))
        .isEqualTo("Account covers asset, liability and equity accounts; none is in scope.");
  }

  @Test
  void isNullWhenScopeOverlapsCategory() {
    Scope scope = Scope.ofTypes("expense");

    assertThat(ScopeDimensionMismatch.check(Dimension.CATEGORY, scope)).isNull();
  }

  @Test
  void isNullWhenScopeIsEveryType() {
    Scope scope = new Scope(Set.of(), true, false);

    assertThat(ScopeDimensionMismatch.check(Dimension.CATEGORY, scope)).isNull();
  }

  @Test
  void isNullForDimensionWithNoFixedAccountTypes() {
    Scope scope = Scope.ofTypes("asset");

    assertThat(ScopeDimensionMismatch.check(Dimension.TAG, scope)).isNull();
  }

  @Test
  void isNullWithNoDimension() {
    Scope scope = Scope.ofTypes("asset");

    assertThat(ScopeDimensionMismatch.check(null, scope)).isNull();
  }
}
