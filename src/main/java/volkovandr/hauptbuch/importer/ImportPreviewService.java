package volkovandr.hauptbuch.importer;

import org.springframework.stereotype.Service;

/**
 * Composes the three pure parser pieces (a2–a4) into the upload {@link ImportPreview} (plan b2) and
 * into the {@link Parsed} value the staging step consumes (plan b3): decode the bytes ({@link
 * QifCharset}), detect the whole-file date order ({@link QifDateFormat}), and parse the canonical
 * representation ({@link QifParser}) — applying the owner's charset / date-order override from
 * {@link PendingImportUpload} when present. Nothing here writes to a staging table.
 *
 * <p>The wire codes ({@code utf_8}, {@code windows_1252}, {@code day_month}, {@code month_day},
 * {@code ambiguous}) match the {@code import_session} / {@code import_file} check constraints so b3
 * persists the confirmed choice unchanged.
 */
@Service
class ImportPreviewService {

  private static final String CHARSET_UTF_8 = "utf_8";
  private static final String CHARSET_WINDOWS_1252 = "windows_1252";
  private static final String ORDER_DAY_MONTH = "day_month";
  private static final String ORDER_MONTH_DAY = "month_day";
  private static final String ORDER_AMBIGUOUS = "ambiguous";

  private final QifParser qifParser;

  ImportPreviewService(QifParser qifParser) {
    this.qifParser = qifParser;
  }

  /**
   * Decode, detect and parse one pending upload — the single place the three pure parser pieces are
   * composed. Throws {@link QifRejectedException} for a file the parser refuses (§4.5); {@link
   * #preview} catches it, {@link ImportStagingService} lets it propagate.
   *
   * @param file the parsed canonical representation
   * @param decoded the decoded bytes (effective charset applied), for the preview lines
   * @param charsetCode the effective charset as a wire code
   * @param detection the whole-file date-order detection with its evidence
   * @param effectiveOrder the date order to read dates with — the override if set, else the
   *     detected one
   * @param dateOrderCode {@code effectiveOrder} as a wire code
   */
  record Parsed(
      ImportedFile file,
      QifCharset.Decoded decoded,
      String charsetCode,
      QifDateFormat.Detection detection,
      QifDateFormat.Order effectiveOrder,
      String dateOrderCode) {}

  /** Decode, detect and parse {@code upload}, honouring any charset / date-order override on it. */
  Parsed parse(PendingImportUpload upload) {
    byte[] bytes = upload.content();
    QifCharset.Encoding chosenCharset = charsetFromCode(upload.charsetChoice());
    QifCharset.Decoded decoded =
        chosenCharset == null ? QifCharset.decode(bytes) : QifCharset.decode(bytes, chosenCharset);
    ImportedFile file = qifParser.parse(upload.moneyAccountName(), decoded.text());
    QifDateFormat.Detection detection = QifDateFormat.detect(decoded.text());
    QifDateFormat.Order chosenOrder = orderFromCode(upload.dateOrderChoice());
    QifDateFormat.Order effectiveOrder = chosenOrder == null ? detection.order() : chosenOrder;
    return new Parsed(
        file,
        decoded,
        charsetCode(decoded.encoding()),
        detection,
        effectiveOrder,
        orderCode(effectiveOrder));
  }

  /**
   * Build the preview for one pending upload, honouring any charset / date-order override on it.
   */
  ImportPreview preview(PendingImportUpload upload) {
    QifCharset.Decoded detected = QifCharset.decode(upload.content());
    try {
      // Parse first: a destroyed account or !Type:Invst is refused outright with a message that
      // names the account (import.md §4.5), and that must win over a date-format complaint.
      Parsed parsed = parse(upload);
      return new ImportPreview(
          parsed.file().proposedAccountType(),
          parsed.charsetCode(),
          charsetCode(detected.encoding()),
          parsed.dateOrderCode(),
          orderCode(parsed.detection().order()),
          parsed.detection().describe(),
          parsed.decoded().previewLines(),
          parsed.file().transactions().size(),
          null);
    } catch (QifRejectedException rejected) {
      QifCharset.Encoding chosenCharset = charsetFromCode(upload.charsetChoice());
      QifCharset.Decoded decoded =
          chosenCharset == null ? detected : QifCharset.decode(upload.content(), chosenCharset);
      return ImportPreview.rejected(
          charsetCode(decoded.encoding()),
          charsetCode(detected.encoding()),
          decoded.previewLines(),
          rejected.getMessage());
    }
  }

  private static String charsetCode(QifCharset.Encoding encoding) {
    return switch (encoding) {
      case UTF_8 -> CHARSET_UTF_8;
      case WINDOWS_1252 -> CHARSET_WINDOWS_1252;
    };
  }

  private static QifCharset.Encoding charsetFromCode(String code) {
    if (code == null) {
      return null;
    }
    return switch (code) {
      case CHARSET_UTF_8 -> QifCharset.Encoding.UTF_8;
      case CHARSET_WINDOWS_1252 -> QifCharset.Encoding.WINDOWS_1252;
      default -> null;
    };
  }

  private static String orderCode(QifDateFormat.Order order) {
    return switch (order) {
      case DAY_MONTH -> ORDER_DAY_MONTH;
      case MONTH_DAY -> ORDER_MONTH_DAY;
      case AMBIGUOUS -> ORDER_AMBIGUOUS;
    };
  }

  private static QifDateFormat.Order orderFromCode(String code) {
    if (code == null) {
      return null;
    }
    return switch (code) {
      case ORDER_DAY_MONTH -> QifDateFormat.Order.DAY_MONTH;
      case ORDER_MONTH_DAY -> QifDateFormat.Order.MONTH_DAY;
      default -> null;
    };
  }
}
