package volkovandr.hauptbuch.statements;

import java.time.LocalDate;

/**
 * One row of the Statements list: the statement with the names the list shows.
 *
 * @param statementId the statement
 * @param accountName the account's name
 * @param profileName the profile's name
 * @param originalFilename the uploaded file's name
 * @param periodStart the period's first day, or null
 * @param periodEnd the period's last day, or null
 * @param lineCount how many lines the statement has
 * @param problemCount how many of them carry a problem
 */
public record StatementRow(
    long statementId,
    String accountName,
    String profileName,
    String originalFilename,
    LocalDate periodStart,
    LocalDate periodEnd,
    int lineCount,
    int problemCount) {}
