package volkovandr.hauptbuch.analytics;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The one muted header line that states a Report's resolved scope (reporting.md §6.4): "why doesn't
 * this match the register" is the question this feature will most often provoke, and the answer is
 * almost always one of these rows.
 */
final class ScopeHeaderText {

  private ScopeHeaderText() {}

  static String render(Scope scope) {
    String types =
        scope.accountTypes().isEmpty()
            ? "All types"
            : scope.accountTypes().stream()
                .sorted()
                .map(ScopeHeaderText::capitalize)
                .collect(Collectors.joining(", "));
    String closed =
        scope.includeClosedAccounts() ? "closed accounts included" : "closed accounts excluded";
    String pending =
        scope.includePendingReview()
            ? "pending-review transactions included"
            : "pending-review transactions excluded";
    return types + " · " + closed + " · " + pending;
  }

  private static String capitalize(String type) {
    return type.substring(0, 1).toUpperCase(Locale.ROOT) + type.substring(1);
  }
}
