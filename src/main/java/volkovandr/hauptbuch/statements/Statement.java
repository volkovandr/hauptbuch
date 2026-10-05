package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One uploaded file for one account (data-model §15): the file on the Pi, the period and the
 * optional balances. The PDF parse columns arrive with slice e.
 *
 * @param statementId the id
 * @param statementProfileId the profile it was read through
 * @param accountId the account it belongs to
 * @param state {@code new}, {@code processing}, {@code processed} or {@code failed}
 * @param originalFilename the uploaded name, for reference
 * @param filePath the file's root-relative path under the statement storage
 * @param periodStart the first day the statement covers
 * @param periodEnd the last day the statement covers
 * @param openingBalance the bank's opening balance, or null (turnover-only check)
 * @param closingBalance the bank's closing balance, or null
 * @param createdAt when the statement was uploaded
 * @param deletedAt when it was soft-deleted, or null
 */
public record Statement(
    long statementId,
    long statementProfileId,
    long accountId,
    String state,
    String originalFilename,
    String filePath,
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal openingBalance,
    BigDecimal closingBalance,
    OffsetDateTime createdAt,
    OffsetDateTime deletedAt) {

  /** The state of a freshly uploaded CSV statement. */
  public static final String STATE_NEW = "new";
}
