package volkovandr.hauptbuch.statements;

import java.time.LocalDate;
import java.util.List;

/**
 * The statement dock's fields — as posted, and as the dock opens: the pre-fill is a {@code
 * DockInput} too, so a refused save re-opens the dock with exactly what was typed. The category
 * fields are what {@code /categories/resolve} produced: a category id, a transfer target (id plus
 * direction) or a person (name, direction).
 *
 * @param date the transaction date
 * @param payeeText a picked or typed payee, optional
 * @param categoryId the resolved category or transfer-target account, or null
 * @param categoryText what the Category field shows, so a re-opened dock can show it again
 * @param transferDirection {@code TO}/{@code FROM} for a transfer target
 * @param personName the resolved person, for a {@code for}/{@code by} target
 * @param personDirection {@code FOR}/{@code BY} alongside {@code personName}
 * @param personRevive the revive decision for a soft-deleted person
 * @param note the transaction note, optional
 * @param tagId the resolved tag ids of the committed chips; never null
 */
public record DockInput(
    LocalDate date,
    String payeeText,
    Long categoryId,
    String categoryText,
    String transferDirection,
    String personName,
    String personDirection,
    String personRevive,
    String note,
    List<Long> tagId) {

  /** Defensively copy the tag ids (null-safe) so the input cannot be mutated after. */
  public DockInput {
    tagId = tagId == null ? List.of() : List.copyOf(tagId);
  }
}
