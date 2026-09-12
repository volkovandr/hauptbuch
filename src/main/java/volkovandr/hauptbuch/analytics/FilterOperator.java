package volkovandr.hauptbuch.analytics;

/**
 * The operator set (reporting.md §6.3). {@link #IS_ONE_OF} on a hierarchy node ({@link
 * FilterField#CATEGORY}, {@link FilterField#ACCOUNT}, {@link FilterField#TAG}) includes its whole
 * subtree — there is no separate "is under" operator. {@link #MATCHES} is a case-insensitive
 * regular expression, offered only for {@link FilterField#PAYEE}. {@link #CONTAINS} is offered only
 * for {@link FilterField#NOTE}.
 */
public enum FilterOperator {
  IS_ONE_OF,
  MATCHES,
  CONTAINS
}
