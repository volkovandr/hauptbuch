package volkovandr.hauptbuch.analytics;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One filter clause of a Report (reporting.md §6.2–§6.3). Filters combine with AND across clauses;
 * the multi-select inside {@link FilterOperator#IS_ONE_OF} is the OR.
 *
 * @param field what is being filtered
 * @param level the transaction/posting reading (§6.2)
 * @param operator the operator (§6.3)
 * @param values operator-dependent: ids for {@code IS_ONE_OF} on {@link FilterField#CATEGORY}/
 *     {@link FilterField#ACCOUNT}/{@link FilterField#TAG} (subtree membership, §6.3) or on {@link
 *     FilterField#PAYEE}/{@link FilterField#PERSON} (exact match — neither is a hierarchy); natural
 *     codes for {@code IS_ONE_OF} on {@link FilterField#CURRENCY}/{@link FilterField#ACCOUNT_TYPE}/
 *     {@link FilterField#LIFECYCLE}/{@link FilterField#RECONCILIATION}; a single regular expression
 *     for {@code MATCHES}; a single substring for {@code CONTAINS}
 */
public record ReportFilter(
    FilterField field, FilterLevel level, FilterOperator operator, List<String> values) {

  /** The legal operators per field (reporting.md §6.3's table). */
  private static final Map<FilterField, Set<FilterOperator>> LEGAL_OPERATORS =
      Map.of(
          FilterField.CATEGORY, Set.of(FilterOperator.IS_ONE_OF),
          FilterField.ACCOUNT, Set.of(FilterOperator.IS_ONE_OF),
          FilterField.TAG, Set.of(FilterOperator.IS_ONE_OF),
          FilterField.PAYEE, Set.of(FilterOperator.IS_ONE_OF, FilterOperator.MATCHES),
          FilterField.PERSON, Set.of(FilterOperator.IS_ONE_OF),
          FilterField.CURRENCY, Set.of(FilterOperator.IS_ONE_OF),
          FilterField.ACCOUNT_TYPE, Set.of(FilterOperator.IS_ONE_OF),
          FilterField.LIFECYCLE, Set.of(FilterOperator.IS_ONE_OF),
          FilterField.RECONCILIATION, Set.of(FilterOperator.IS_ONE_OF),
          FilterField.NOTE, Set.of(FilterOperator.CONTAINS));

  /**
   * Defensively copies {@code values}, rejects an empty list, and rejects an operator {@code field}
   * does not offer (§6.3) — a category error the UI must never present, checked here so a bad
   * combination fails at spec construction rather than producing a query with no matching SQL
   * branch.
   */
  public ReportFilter {
    if (values == null || values.isEmpty()) {
      throw new IllegalArgumentException("A filter needs at least one value.");
    }
    if (!LEGAL_OPERATORS.get(field).contains(operator)) {
      throw new IllegalArgumentException(operator + " is not offered for " + field + " (§6.3).");
    }
    values = List.copyOf(values);
  }
}
