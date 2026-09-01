package volkovandr.hauptbuch.importer;

import java.util.Optional;
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
   * The bytes decoded once: the strict-UTF-8 probe result and, when the owner overrode the charset,
   * the re-decode under that choice. {@code effective == detected} when there is no override.
   *
   * @param effective the decode the file is actually parsed / previewed from
   * @param detected the strict-probe decode — its encoding is the "(detected)" hint on the preview
   */
  private record Decoded(QifCharset.Decoded effective, QifCharset.Decoded detected) {}

  /**
   * The result of composing the three pure parser pieces. Throws {@link QifRejectedException} for a
   * file the parser refuses (§4.5); {@link #preview} catches it, {@link ImportStagingService} lets
   * it propagate.
   *
   * @param file the parsed canonical representation
   * @param decoded the decoded bytes (effective charset applied), for the preview lines
   * @param charsetCode the effective charset as a wire code
   * @param detectedCharsetCode the strict-probe charset as a wire code — the "(detected)" hint
   * @param detection the whole-file date-order detection with its evidence
   * @param effectiveOrder the date order to read dates with — the override if set, else the
   *     detected one
   * @param dateOrderCode {@code effectiveOrder} as a wire code
   */
  record Parsed(
      ImportedFile file,
      QifCharset.Decoded decoded,
      String charsetCode,
      String detectedCharsetCode,
      QifDateFormat.Detection detection,
      QifDateFormat.Order effectiveOrder,
      String dateOrderCode) {}

  private static Decoded decode(PendingImportUpload upload) {
    byte[] bytes = upload.content();
    QifCharset.Decoded detected = QifCharset.decode(bytes);
    QifCharset.Encoding chosen = charsetFromCode(upload.charsetChoice());
    return new Decoded(chosen == null ? detected : QifCharset.decode(bytes, chosen), detected);
  }

  /**
   * The Money account the file names in its own opening-balance record (import.md §4.1/§5.1), read
   * back at upload to pre-fill the preview. Empty when the file has no such record — or cannot be
   * decoded/parsed at all, in which case the preview surfaces the real rejection.
   */
  Optional<String> deduceAccountName(PendingImportUpload upload) {
    try {
      return qifParser.detectAccountName(decode(upload).effective().text());
    } catch (QifRejectedException rejected) {
      return Optional.empty();
    }
  }

  /** Decode, detect and parse {@code upload}, honouring any charset / date-order override on it. */
  Parsed parse(PendingImportUpload upload) {
    Decoded bytes = decode(upload);
    QifCharset.Decoded decoded = bytes.effective();
    ImportedFile file = qifParser.parse(upload.moneyAccountName(), decoded.text());
    QifDateFormat.Detection detection = QifDateFormat.detect(decoded.text());
    QifDateFormat.Order chosenOrder = orderFromCode(upload.dateOrderChoice());
    QifDateFormat.Order effectiveOrder = chosenOrder == null ? detection.order() : chosenOrder;
    return new Parsed(
        file,
        decoded,
        charsetCode(decoded.encoding()),
        charsetCode(bytes.detected().encoding()),
        detection,
        effectiveOrder,
        orderCode(effectiveOrder));
  }

  /**
   * Build the preview for one pending upload, honouring any charset / date-order override on it.
   */
  ImportPreview preview(PendingImportUpload upload) {
    try {
      // Parse first: a destroyed account or !Type:Invst is refused outright with a message that
      // names the account (import.md §4.5), and that must win over a date-format complaint.
      Parsed parsed = parse(upload);
      return new ImportPreview(
          parsed.file().proposedAccountType(),
          parsed.charsetCode(),
          parsed.detectedCharsetCode(),
          parsed.dateOrderCode(),
          orderCode(parsed.detection().order()),
          parsed.detection().describe(),
          parsed.decoded().previewLines(),
          parsed.file().transactions().size(),
          upload.moneyAccountName(),
          upload.accountNameDeduced(),
          null);
    } catch (QifRejectedException rejected) {
      Decoded bytes = decode(upload);
      return ImportPreview.rejected(
          charsetCode(bytes.effective().encoding()),
          charsetCode(bytes.detected().encoding()),
          bytes.effective().previewLines(),
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
