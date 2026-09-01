package volkovandr.hauptbuch.importer;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Composes the three pure parser pieces (a2–a4) into the upload {@link ImportPreview} (plan b2):
 * decode the bytes ({@link QifCharset}), detect the whole-file date order ({@link QifDateFormat}),
 * and parse the canonical representation ({@link QifParser}) — applying the owner's charset /
 * date-order override from {@link PendingImportUpload} when present. Nothing here writes to a
 * staging table; the preview is recomputed on every render.
 *
 * <p>The wire codes ({@code utf_8}, {@code windows_1252}, {@code day_month}, {@code month_day},
 * {@code ambiguous}) match the {@code import_session} / {@code import_file} check constraints so b3
 * can persist the confirmed choice unchanged.
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
   * Build the preview for one pending upload, honouring any charset / date-order override on it.
   */
  ImportPreview preview(PendingImportUpload upload) {
    byte[] bytes = upload.content();
    QifCharset.Decoded detected = QifCharset.decode(bytes);
    QifCharset.Encoding chosenCharset = charsetFromCode(upload.charsetChoice());
    QifCharset.Decoded decoded =
        chosenCharset == null ? detected : QifCharset.decode(bytes, chosenCharset);
    List<String> previewLines = decoded.previewLines();
    try {
      // Parse first: a destroyed account or !Type:Invst is refused outright with a message that
      // names the account (import.md §4.5), and that must win over a date-format complaint.
      ImportedFile file = qifParser.parse(upload.moneyAccountName(), decoded.text());
      QifDateFormat.Detection detection = QifDateFormat.detect(decoded.text());
      QifDateFormat.Order chosenOrder = orderFromCode(upload.dateOrderChoice());
      QifDateFormat.Order effectiveOrder = chosenOrder == null ? detection.order() : chosenOrder;
      return new ImportPreview(
          file.proposedAccountType(),
          charsetCode(decoded.encoding()),
          charsetCode(detected.encoding()),
          orderCode(effectiveOrder),
          orderCode(detection.order()),
          detection.describe(),
          previewLines,
          file.transactions().size(),
          null);
    } catch (QifRejectedException rejected) {
      return ImportPreview.rejected(
          charsetCode(decoded.encoding()),
          charsetCode(detected.encoding()),
          previewLines,
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
