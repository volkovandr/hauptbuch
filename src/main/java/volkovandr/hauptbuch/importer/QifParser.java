package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Parses an already-decoded QIF file's text into its canonical representation (import.md §3/§4) —
 * slice a2 of the import build sequence. Charset decoding and whole-file date-format detection both
 * happen around this, not inside it (a3): {@link ImportedTransaction#rawDate()} stays the literal
 * source text, never guessed at here.
 *
 * <p>Handles Money's asset/liability account headers and simple, one-line transactions only.
 * Splits, the self-transfer opening-balance marker, and the destroyed-account-name rejection all
 * land at a4; encountering a split here is refused rather than silently dropping its extra legs.
 */
@Component
public class QifParser {

  private static final Set<String> ASSET_HEADERS =
      Set.of("!Type:Bank", "!Type:Cash", "!Type:Oth A");
  private static final Set<String> LIABILITY_HEADERS = Set.of("!Type:CCard", "!Type:Oth L");
  private static final String INVESTMENT_HEADER = "!Type:Invst";

  private static final String ASSET_TYPE = "asset";
  private static final String LIABILITY_TYPE = "liability";

  private static final char ADDRESS_FIELD_LETTER = 'A';
  private static final Set<Character> SPLIT_FIELD_LETTERS = Set.of('S', 'E', '$');

  /** Parse a fully decoded QIF file's text (§4.1) into its canonical representation. */
  public ImportedFile parse(String text) {
    QifRecordReader.Result read = QifRecordReader.read(text);
    String accountType = proposeAccountType(read.header());
    List<ImportedTransaction> transactions =
        read.records().stream().map(this::toTransaction).toList();
    return new ImportedFile(accountType, transactions);
  }

  private String proposeAccountType(String header) {
    if (ASSET_HEADERS.contains(header)) {
      return ASSET_TYPE;
    }
    if (LIABILITY_HEADERS.contains(header)) {
      return LIABILITY_TYPE;
    }
    if (INVESTMENT_HEADER.equals(header)) {
      throw new QifRejectedException(
          "This file is a Money investment account (!Type:Invst) — Hauptbuch has no investment"
              + " support and cannot import it.");
    }
    throw new QifRejectedException("Unrecognised QIF account header: \"" + header + "\".");
  }

  private ImportedTransaction toTransaction(List<String> fieldLines) {
    RawFields raw = new RawFields();
    for (String fieldLine : fieldLines) {
      if (fieldLine.charAt(0) == ADDRESS_FIELD_LETTER) {
        continue; // payee mailing address — ignored (§4.4); the payee-name parser is the source
      }
      assign(raw, fieldLine);
    }
    if (raw.rawDate == null) {
      throw new QifRejectedException("A QIF record is missing its D (date) field.");
    }
    if (raw.targetText == null) {
      throw new QifRejectedException("A QIF record is missing its L (category/account) field.");
    }

    BigDecimal amount =
        QifAmounts.parse(raw.amountText != null ? raw.amountText : raw.duplicateAmountText);
    ImportedLine line = new ImportedLine(amount, null, toTarget(raw.targetText));

    return new ImportedTransaction(
        raw.rawDate,
        raw.payeeText,
        raw.memo,
        raw.referenceNumber,
        ClearedStatus.fromCode(raw.clearedCode),
        List.of(line));
  }

  private static void assign(RawFields raw, String fieldLine) {
    char letter = fieldLine.charAt(0);
    if (SPLIT_FIELD_LETTERS.contains(letter)) {
      throw new QifRejectedException(
          "This record has a split (S/E/$) — splits are not supported yet (a4).");
    }
    String value = fieldLine.substring(1);
    if (tryFundingField(raw, letter, value) || tryDetailField(raw, letter, value)) {
      return;
    }
    throw new QifRejectedException("Unrecognised QIF field: \"" + fieldLine + "\".");
  }

  /** {@code D}/{@code T}/{@code U}/{@code P} — split from {@link #tryDetailField} for size only. */
  private static boolean tryFundingField(RawFields raw, char letter, String value) {
    switch (letter) {
      case 'D' -> raw.rawDate = value;
      case 'T' -> raw.amountText = value;
      case 'U' -> raw.duplicateAmountText = value;
      case 'P' -> raw.payeeText = value;
      default -> {
        return false;
      }
    }
    return true;
  }

  /** {@code M}/{@code N}/{@code C}/{@code L} — the rest of {@link #assign}'s known fields. */
  private static boolean tryDetailField(RawFields raw, char letter, String value) {
    switch (letter) {
      case 'M' -> raw.memo = value;
      case 'N' -> raw.referenceNumber = value;
      case 'C' -> raw.clearedCode = value;
      case 'L' -> raw.targetText = value;
      default -> {
        return false;
      }
    }
    return true;
  }

  private ImportedTarget toTarget(String rawTarget) {
    if (rawTarget.startsWith("[") && rawTarget.endsWith("]")) {
      return new ImportedTarget.AccountReference(rawTarget.substring(1, rawTarget.length() - 1));
    }
    return new ImportedTarget.CategoryPath(rawTarget);
  }

  /** The still-unparsed field values of one record, gathered as its lines are walked. */
  private static final class RawFields {
    private String rawDate;
    private String amountText;
    private String duplicateAmountText;
    private String payeeText;
    private String memo;
    private String referenceNumber;
    private String clearedCode;
    private String targetText;
  }
}
