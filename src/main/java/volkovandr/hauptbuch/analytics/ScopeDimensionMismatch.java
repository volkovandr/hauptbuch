package volkovandr.hauptbuch.analytics;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The "scope misses the dimension" message (reporting.md §6.1): {@link Dimension#CATEGORY} and
 * {@link Dimension#ACCOUNT} both walk the {@code account} tree restricted to {@link
 * Scope#accountTypes()} (they differ only in which types a Report pairs them with, {@link
 * Dimension}'s own javadoc), so a Report grouping by one while scoped to account types the
 * dimension never contains comes back with no candidate row/column at all — not a blank cell, an
 * empty axis. Scope never follows the dimensions (§6.1), so this is surfaced as an explanation
 * rather than corrected automatically.
 *
 * <p>Takes the already-resolved {@link AxisPlan#nonDateDim()} rather than a {@link ReportSpec} — a
 * spec's own row/series-slot resolution is {@link ReportEngine}'s job alone; {@link
 * ReportGridBuilder#build} is this check's one caller, computed once and carried on {@link
 * ReportGrid#refusalMessage()} for every renderer to read.
 */
final class ScopeDimensionMismatch {

  private static final Map<Dimension, List<String>> EXPECTED_TYPES =
      Map.of(
          Dimension.CATEGORY, List.of("income", "expense"),
          Dimension.ACCOUNT, List.of("asset", "liability", "equity"));

  private ScopeDimensionMismatch() {}

  /**
   * {@link Dimension#CATEGORY}/{@link Dimension#ACCOUNT}'s own backing account types, or {@code
   * null} for any other dimension. Reused by {@link ReportFilterViewAssembler}'s Account filter
   * section (plan stage d3-4) so the pairing is not hardcoded a second time.
   */
  static List<String> accountTypesFor(Dimension dimension) {
    return EXPECTED_TYPES.get(dimension);
  }

  /**
   * The account types a Category or Account query walks (reporting.md §4, whatever else the scope
   * holds): the dimension's own types that are in scope — all of them for an every-type (empty)
   * scope. Empty when the scope has none of them, and then nothing is queried at all.
   */
  static Optional<List<String>> ownAccountTypes(Dimension dimension, List<String> scopeTypes) {
    List<String> own = accountTypesFor(dimension);
    List<String> inScope =
        scopeTypes.isEmpty() ? own : own.stream().filter(scopeTypes::contains).toList();
    return inScope.isEmpty() ? Optional.empty() : Optional.of(inScope);
  }

  /**
   * {@code null} when {@code nonDateDim} is not {@link Dimension#CATEGORY}/{@link
   * Dimension#ACCOUNT}, when scope is every type ({@link Scope#accountTypes()} empty), or when
   * scope overlaps the dimension's own account types.
   */
  static String check(Dimension nonDateDim, Scope scope) {
    List<String> expected = nonDateDim == null ? null : EXPECTED_TYPES.get(nonDateDim);
    if (expected == null
        || scope.accountTypes().isEmpty()
        || !Collections.disjoint(expected, scope.accountTypes())) {
      return null;
    }
    String pronoun = expected.size() == 2 ? "neither" : "none";
    return capitalize(nonDateDim.name())
        + " covers "
        + joinNatural(expected)
        + " accounts; "
        + pronoun
        + " is in scope.";
  }

  private static String joinNatural(List<String> items) {
    if (items.size() == 1) {
      return items.get(0);
    }
    String allButLast = String.join(", ", items.subList(0, items.size() - 1));
    return allButLast + " and " + items.get(items.size() - 1);
  }

  private static String capitalize(String enumName) {
    String lower = enumName.toLowerCase(Locale.ROOT);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }
}
