package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One booking the bank reports (data-model §15, statements.md §3.3). The amount is always in the
 * account's currency, {@code +} = money in. A line with a {@code problem} could not be read or is
 * in another currency; it is kept and never matched.
 *
 * @param statementLineId the id, or null before the first save
 * @param sortOrder the line's position in the file
 * @param bookingDate the bank's booking date
 * @param valueDate the bank's value date, optional
 * @param amount the signed amount in the account's currency
 * @param counterparty who the bank says the other side is
 * @param description the bank's free text
 * @param bankCategory the bank's own category label, a hint only
 * @param rawText the source row
 * @param problem why the line cannot be matched, or null
 */
public record StatementLine(
    Long statementLineId,
    int sortOrder,
    LocalDate bookingDate,
    LocalDate valueDate,
    BigDecimal amount,
    String counterparty,
    String description,
    String bankCategory,
    String rawText,
    String problem) {}
