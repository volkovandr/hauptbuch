package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): {@link ReportFilter}'s own decidable-without-the-DB validation — the
 * legal operator per field (reporting.md §6.3's table) and the non-empty-values rule. Which SQL
 * predicate each combination compiles to is {@code ReportQuerySqlLogicTest}'s job.
 */
class ReportFilterTest {

  @Test
  void rejectsEmptyValues() {
    assertThatThrownBy(
            () ->
                new ReportFilter(
                    FilterField.CATEGORY, FilterLevel.POSTING, FilterOperator.IS_ONE_OF, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMatchesOnHierarchyField() {
    assertThatThrownBy(
            () ->
                new ReportFilter(
                    FilterField.CATEGORY,
                    FilterLevel.POSTING,
                    FilterOperator.MATCHES,
                    List.of("1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsIsOneOfOnNote() {
    assertThatThrownBy(
            () ->
                new ReportFilter(
                    FilterField.NOTE,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of("x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsContainsOnAnythingButNote() {
    assertThatThrownBy(
            () ->
                new ReportFilter(
                    FilterField.PAYEE,
                    FilterLevel.TRANSACTION,
                    FilterOperator.CONTAINS,
                    List.of("x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsPayeeIsOneOfAndMatches() {
    assertThatCode(
            () ->
                new ReportFilter(
                    FilterField.PAYEE,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of("1")))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                new ReportFilter(
                    FilterField.PAYEE,
                    FilterLevel.TRANSACTION,
                    FilterOperator.MATCHES,
                    List.of("x")))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsNoteContains() {
    assertThatCode(
            () ->
                new ReportFilter(
                    FilterField.NOTE, FilterLevel.POSTING, FilterOperator.CONTAINS, List.of("x")))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsPostingLevelAccountTypeFilter() {
    assertThatThrownBy(
            () ->
                new ReportFilter(
                    FilterField.ACCOUNT_TYPE,
                    FilterLevel.POSTING,
                    FilterOperator.IS_ONE_OF,
                    List.of("asset")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsTransactionLevelAccountTypeFilter() {
    assertThatCode(
            () ->
                new ReportFilter(
                    FilterField.ACCOUNT_TYPE,
                    FilterLevel.TRANSACTION,
                    FilterOperator.IS_ONE_OF,
                    List.of("asset")))
        .doesNotThrowAnyException();
  }
}
