package volkovandr.hauptbuch.statements;

import java.time.LocalDate;

/**
 * The statement dock's fields as posted. The category fields are what {@code /categories/resolve}
 * produced: a category id, a transfer target (id plus direction) or a person (name, direction).
 *
 * @param date the transaction date
 * @param payeeText a picked or typed payee, optional
 * @param categoryId the resolved category or transfer-target account, or null
 * @param transferDirection {@code TO}/{@code FROM} for a transfer target
 * @param personName the resolved person, for a {@code for}/{@code by} target
 * @param personDirection {@code FOR}/{@code BY} alongside {@code personName}
 * @param personRevive the revive decision for a soft-deleted person
 * @param note the transaction note, optional
 */
public record DockInput(
    LocalDate date,
    String payeeText,
    Long categoryId,
    String transferDirection,
    String personName,
    String personDirection,
    String personRevive,
    String note) {}
