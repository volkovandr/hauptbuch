package volkovandr.hauptbuch.importer;

import java.time.OffsetDateTime;

/**
 * One staged QIF export (import.md §11; plan b3). The file does not name its account — the owner
 * states it ({@code moneyAccountName}, §4.1) — and {@code charset} / {@code dateOrder} are what it
 * was actually decoded and parsed with after the owner confirmed or overrode the detection
 * (§4.3/§4.4). {@code filename} is reference only; it carries no identity (§2). Removing the row
 * cascades to its {@code import_transaction} / {@code import_posting} children.
 *
 * @param importFileId surrogate PK; null for a not-yet-persisted row
 * @param importSessionId the campaign this file belongs to
 * @param sourceFilename the uploaded filename — reference only (§2); the {@code
 *     import_file.filename} column, named as {@link PendingImportUpload} and {@link
 *     ImportUploadView} name it
 * @param moneyAccountName the Money account the owner stated this file is for (§4.1)
 * @param charset {@code utf_8} / {@code windows_1252} — how the bytes were decoded
 * @param dateOrder {@code day_month} / {@code month_day} — how the dates were read
 * @param proposedAccountType {@code asset} / {@code liability} from the {@code !Type:} header, or
 *     null when the header proposed neither
 * @param transactionCount the number of {@code import_transaction} rows this file staged
 * @param stagedAt when the file was staged
 */
public record ImportFile(
    Long importFileId,
    Long importSessionId,
    String sourceFilename,
    String moneyAccountName,
    String charset,
    String dateOrder,
    String proposedAccountType,
    int transactionCount,
    OffsetDateTime stagedAt) {}
