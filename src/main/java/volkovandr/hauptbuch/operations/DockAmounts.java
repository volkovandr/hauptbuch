package volkovandr.hauptbuch.operations;

import volkovandr.hauptbuch.ledger.CrossCurrencyFields;

/**
 * The dock's amount fields as the server proposes them for a submitted form (issue
 * transaction-register-ui/27): the layout with its Base text, plus the Off account text and the
 * suggestions behind both, which the dock carries back in hidden fields so the next refresh can
 * tell a suggestion it may replace from a value the operator typed.
 *
 * @param fields the amount-field layout; its {@code baseAmountText} is the Base field's value
 * @param offAccountText the Off account field's value; null when not shown or nothing to propose
 * @param offAccountSuggestion the Off account value proposed this time; null when the field holds a
 *     typed value or there was nothing to propose
 * @param baseSuggestion the Base value proposed this time, read like {@code offAccountSuggestion}
 */
record DockAmounts(
    CrossCurrencyFields fields,
    String offAccountText,
    String offAccountSuggestion,
    String baseSuggestion) {}
