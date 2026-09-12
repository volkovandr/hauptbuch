package volkovandr.hauptbuch.analytics;

import java.util.List;

/**
 * One filter clause of a Report (reporting.md §6.2–§6.3). Filters combine with AND across clauses;
 * the multi-select inside {@link FilterOperator#IS_ONE_OF} is the OR.
 *
 * @param field what is being filtered
 * @param level the transaction/posting reading (§6.2)
 * @param operator the operator (§6.3)
 * @param values operator-dependent: node ids for {@code IS_ONE_OF} on a hierarchy field, natural
 *     codes for {@code IS_ONE_OF} on {@link FilterField#CURRENCY}/{@link FilterField#ACCOUNT_TYPE}/
 *     {@link FilterField#LIFECYCLE}/{@link FilterField#RECONCILIATION}, a single regular expression
 *     for {@code MATCHES}, a single substring for {@code CONTAINS}
 */
public record ReportFilter(
    FilterField field, FilterLevel level, FilterOperator operator, List<String> values) {

  /** Defensively copies {@code values} and rejects an empty list. */
  public ReportFilter {
    if (values == null || values.isEmpty()) {
      throw new IllegalArgumentException("A filter needs at least one value.");
    }
    values = List.copyOf(values);
  }
}
