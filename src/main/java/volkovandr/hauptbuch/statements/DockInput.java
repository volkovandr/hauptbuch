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
 * @param amount the amount as the register's dock would type it, used only where the operator may
 *     change it (an extra); a line's dock books the bank's amount and ignores it
 * @param categoryCurrencyCode the counterpart leg's currency when the booked transaction is
 *     cross-currency, else null
 * @param categoryAmount the counterpart leg's native magnitude (cross-currency only)
 * @param baseAmount the frozen base-currency magnitude, asked for only when neither leg is the base
 *     currency (cross-currency only)
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
    List<Long> tagId,
    String amount,
    String categoryCurrencyCode,
    String categoryAmount,
    String baseAmount) {

  /** Defensively copy the tag ids (null-safe) so the input cannot be mutated after. */
  public DockInput {
    tagId = tagId == null ? List.of() : List.copyOf(tagId);
  }

  /** This input with a different frozen base amount (blank asks the operator to enter one). */
  public DockInput withBaseAmount(String newBaseAmount) {
    return new DockInput(
        date,
        payeeText,
        categoryId,
        categoryText,
        transferDirection,
        personName,
        personDirection,
        personRevive,
        note,
        tagId,
        amount,
        categoryCurrencyCode,
        categoryAmount,
        newBaseAmount);
  }
}
