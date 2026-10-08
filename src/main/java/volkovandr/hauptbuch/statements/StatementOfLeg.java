package volkovandr.hauptbuch.statements;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * The statement a reconciled leg is matched to, as the register's dock names it (statements.md
 * §6.4).
 *
 * @param accountName the leg's account
 * @param periodStart the statement's first day, or null when the period is unknown
 * @param originalFilename the uploaded file's name, used when the period is unknown
 */
public record StatementOfLeg(String accountName, LocalDate periodStart, String originalFilename) {

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

  /** The notice line, e.g. {@code BankAaa-EUR leg reconciled — statement 2026-05}. */
  public String notice() {
    String statement = periodStart == null ? originalFilename : periodStart.format(MONTH);
    return accountName + " leg reconciled — statement " + statement;
  }
}
