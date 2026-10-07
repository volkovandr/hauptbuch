package volkovandr.hauptbuch.statements;

/**
 * What the statement page's dock opens with for a missing line (statements.md §6.4).
 *
 * @param lineId the line being created
 * @param date the booking date as ISO text
 * @param accountName the statement's account
 * @param amount the line's signed amount, German-formatted
 * @param bankText the bank's counterparty and description, shown so the operator can see what the
 *     payee was guessed from
 * @param payeeText the guessed payee in {@code Name - City - Country} form, or empty
 * @param categoryText the payee's last category, or empty
 * @param categoryId that category's id, or null
 * @param bankCategory the bank's own category label, a hint beside the picker
 */
public record DockPrefill(
    long lineId,
    String date,
    String accountName,
    String amount,
    String bankText,
    String payeeText,
    String categoryText,
    Long categoryId,
    String bankCategory) {}
