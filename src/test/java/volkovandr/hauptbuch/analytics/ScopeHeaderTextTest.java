package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tier (CLAUDE.md §6): the Report header's muted scope line (reporting.md §6.4). */
class ScopeHeaderTextTest {

  @Test
  void listsTypesAlphabeticallyAndTheDefaultToggles() {
    Scope scope = Scope.ofTypes("expense", "income");

    assertThat(ScopeHeaderText.render(scope))
        .isEqualTo(
            "Expense, Income · closed accounts included · pending-review transactions excluded");
  }

  @Test
  void statesEveryTypeIsInScopeWhenAccountTypesIsEmpty() {
    Scope scope = new Scope(Set.of(), true, false);

    assertThat(ScopeHeaderText.render(scope)).startsWith("All types ·");
  }

  @Test
  void reflectsTheNonDefaultToggles() {
    Scope scope = new Scope(Set.of("asset"), false, true);

    assertThat(ScopeHeaderText.render(scope))
        .isEqualTo("Asset · closed accounts excluded · pending-review transactions included");
  }
}
