package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Parses an already-decoded QIF file's text into its canonical representation (import.md §3/§4) —
 * slice a of the import build sequence. Charset decoding and whole-file date-format detection both
 * happen around this, not inside it (a3): {@link ImportedTransaction#rawDate()} stays the literal
 * source text, never guessed at here.
 *
 * <p>Handles Money's asset/liability account headers, simple one-line transactions, splits ({@code
 * S}/{@code E}/{@code $}), transfers ({@code [Account]}), the opening-balance self-transfer (§5.1)
 * and Money's {@code /Class} tag suffix (§8, resolved by {@link QifTarget}). {@code !Type:Invst}
 * and any file naming a destroyed account (§4.5) are refused outright rather than staged with a
 * guess (CLAUDE.md §0).
 */
@Component
public class QifParser {

  private static final Set<String> ASSET_HEADERS =
      Set.of("!Type:Bank", "!Type:Cash", "!Type:Oth A");
  private static final Set<String> LIABILITY_HEADERS = Set.of("!Type:CCard", "!Type:Oth L");
  private static final String INVESTMENT_HEADER = "!Type:Invst";

  private static final String ASSET_TYPE = "asset";
  private static final String LIABILITY_TYPE = "liability";

  /** §5.1: Money writes an account's opening balance as a self-transfer with this payee. */
  private static final String OPENING_BALANCE_PAYEE = "Opening Balance";

  /**
   * Parse a fully decoded QIF file's text (§4.1) into its canonical representation.
   *
   * @param moneyAccountName the Money account the file is for — the file itself has no field for
   *     it, but usually names it in the opening-balance self-transfer (§5.1), read back by {@link
   *     #detectAccountName}. Pass that (or the owner's hand-stated name) to seed {@link
   *     ImportedFile#referencedAccountNames()}, or {@code null} when it is not yet known (the
   *     preview before the owner has confirmed it); a blank string is still rejected.
   * @param text the fully decoded file text
   */
  public ImportedFile parse(String moneyAccountName, String text) {
    if (moneyAccountName != null && moneyAccountName.isBlank()) {
      throw new IllegalArgumentException(
          "The Money account name must be non-blank when supplied (import.md §4.1) — pass null when"
              + " it is not yet known.");
    }
    QifRecordReader.Result read = QifRecordReader.read(text);
    String accountType = proposeAccountType(read.header());
    List<ImportedTransaction> transactions =
        read.records().stream().map(QifParser::toTransaction).toList();
    Set<String> referenced = referencedAccountNames(moneyAccountName, transactions);
    rejectDestroyedAccounts(referenced);
    return new ImportedFile(accountType, referenced, transactions);
  }

  /**
   * The account name Money's opening-balance record names (§4.1/§5.1): the {@code [Account]} target
   * of the single-line self-transfer whose payee is {@value #OPENING_BALANCE_PAYEE}. Empty when the
   * file has no such record — then the owner states the account by hand on the upload preview. Pure
   * (§3); used to pre-fill the preview so the common case needs no typing.
   */
  public Optional<String> detectAccountName(String text) {
    return parse(null, text).transactions().stream()
        .filter(ImportedTransaction::openingBalance)
        .map(transaction -> transaction.lines().get(0).target())
        .filter(ImportedTarget.AccountReference.class::isInstance)
        .map(target -> ((ImportedTarget.AccountReference) target).accountName())
        .findFirst();
  }

  private static String proposeAccountType(String header) {
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

  private static ImportedTransaction toTransaction(List<String> fieldLines) {
    RawRecord raw = RawRecord.from(fieldLines);
    List<ImportedLine> lines =
        raw.splitLegs().isEmpty() ? List.of(raw.simpleLine()) : raw.splitLines();
    return new ImportedTransaction(
        raw.requireDate(),
        classifyPayee(raw.payeeText()),
        isDestroyedPayee(raw.payeeText()),
        raw.memo(),
        raw.referenceNumber(),
        ClearedStatus.fromCode(raw.clearedCode()),
        isOpeningBalance(raw.payeeText(), lines),
        lines);
  }

  /**
   * §4.4: a payee that is <em>entirely</em> {@code ?}/whitespace carries no information and would
   * collide with every other fully destroyed name, so it yields no payee at all; a partially
   * destroyed name still distinguishes and is kept verbatim.
   */
  private static String classifyPayee(String rawPayee) {
    if (rawPayee == null || rawPayee.isBlank() || QifText.isDestroyed(rawPayee)) {
      return null;
    }
    return rawPayee;
  }

  /** §4.4: the {@code P} field was present but wholly {@code ?}/whitespace — counted separately. */
  private static boolean isDestroyedPayee(String rawPayee) {
    return rawPayee != null && !rawPayee.isBlank() && QifText.isDestroyed(rawPayee);
  }

  /**
   * §5.1: Money exports an opening balance as a single-line self-transfer whose payee is {@value
   * #OPENING_BALANCE_PAYEE} — recognised by that marker and shape, so the file's own account name
   * ({@code [Account]} on that one line) does not have to be known in advance to spot it.
   */
  private static boolean isOpeningBalance(String payeeText, List<ImportedLine> lines) {
    return payeeText != null
        && OPENING_BALANCE_PAYEE.equalsIgnoreCase(payeeText.strip())
        && lines.size() == 1
        && lines.get(0).target() instanceof ImportedTarget.AccountReference;
  }

  private static Set<String> referencedAccountNames(
      String moneyAccountName, List<ImportedTransaction> transactions) {
    Set<String> names = new LinkedHashSet<>();
    if (moneyAccountName != null) {
      names.add(moneyAccountName);
    }
    transactions.stream()
        .flatMap(transaction -> transaction.lines().stream())
        .map(ImportedLine::target)
        .filter(ImportedTarget.AccountReference.class::isInstance)
        .map(target -> ((ImportedTarget.AccountReference) target).accountName())
        .forEach(names::add);
    return names;
  }

  private static void rejectDestroyedAccounts(Set<String> referencedAccountNames) {
    boolean anyDestroyed =
        referencedAccountNames.stream()
            .anyMatch(name -> !name.isBlank() && QifText.isDestroyed(name));
    if (anyDestroyed) {
      throw new QifRejectedException(
          "This file references an account whose name was destroyed on export (it is all \"?\")."
              + " Rename that account in Money and re-export — Hauptbuch will not guess which"
              + " account it is.");
    }
  }

  /**
   * One QIF record's field values, gathered as its lines are walked (import.md §4.2). Split legs
   * are kept in source order — a leg is complete only once its {@code $} amount arrives.
   */
  private record RawRecord(
      String rawDate,
      String amountText,
      String duplicateAmountText,
      String payeeText,
      String memo,
      String referenceNumber,
      String clearedCode,
      String targetText,
      List<SplitLeg> splitLegs) {

    private static RawRecord from(List<String> fieldLines) {
      Builder builder = new Builder();
      for (String fieldLine : fieldLines) {
        builder.accept(fieldLine.charAt(0), fieldLine.substring(1), fieldLine);
      }
      return builder.build();
    }

    private String requireDate() {
      if (rawDate == null) {
        throw new QifRejectedException("A QIF record is missing its D (date) field.");
      }
      return rawDate;
    }

    private String headerAmountText() {
      return amountText != null ? amountText : duplicateAmountText;
    }

    private ImportedLine simpleLine() {
      if (targetText == null) {
        throw new QifRejectedException("A QIF record is missing its L (category/account) field.");
      }
      QifTarget.Resolved resolved = QifTarget.resolve(targetText);
      return new ImportedLine(
          QifAmounts.parse(headerAmountText()), null, resolved.className(), resolved.target());
    }

    private List<ImportedLine> splitLines() {
      String headerAmount = headerAmountText();
      if (headerAmount == null) {
        throw new QifRejectedException("A split QIF record is missing its T (total) field.");
      }
      List<ImportedLine> lines = new ArrayList<>();
      for (SplitLeg leg : splitLegs) {
        lines.add(splitLine(leg));
      }
      BigDecimal sum =
          lines.stream().map(ImportedLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal headerTotal = QifAmounts.parse(headerAmount);
      if (sum.compareTo(headerTotal) != 0) {
        throw new QifRejectedException(
            "This split's lines sum to "
                + sum.toPlainString()
                + " but the record total (T) is "
                + headerTotal.toPlainString()
                + " — Hauptbuch never adjusts a split to make it balance.");
      }
      return lines;
    }

    private static ImportedLine splitLine(SplitLeg leg) {
      if (leg.amountText == null) {
        throw new QifRejectedException("The split line \"S" + leg.target + "\" has no $ amount.");
      }
      QifTarget.Resolved resolved = QifTarget.resolve(leg.target);
      return new ImportedLine(
          QifAmounts.parse(leg.amountText),
          QifText.blankToNull(leg.memo),
          resolved.className(),
          resolved.target());
    }
  }

  /** One {@code S}/{@code E}/{@code $} split leg, filled in as its three lines are walked. */
  private static final class SplitLeg {
    private final String target;
    private String memo;
    private String amountText;

    private SplitLeg(String target) {
      this.target = target;
    }
  }

  /** Accumulates one record's fields as {@link RawRecord#from} walks its lines. */
  private static final class Builder {
    private String rawDate;
    private String amountText;
    private String duplicateAmountText;
    private String payeeText;
    private String memo;
    private String referenceNumber;
    private String clearedCode;
    private String targetText;
    private final List<SplitLeg> splitLegs = new ArrayList<>();

    private void accept(char letter, String value, String fieldLine) {
      if (assignFundingField(letter, value)
          || assignDetailField(letter, value)
          || assignSplitField(letter, value, fieldLine)) {
        return;
      }
      throw new QifRejectedException("Unrecognised QIF field: \"" + fieldLine + "\".");
    }

    /** {@code D}/{@code T}/{@code U}/{@code P} — the funding fields (§4.2). */
    private boolean assignFundingField(char letter, String value) {
      switch (letter) {
        case 'D' -> rawDate = value;
        case 'T' -> amountText = value;
        case 'U' -> duplicateAmountText = value;
        case 'P' -> payeeText = value;
        default -> {
          return false;
        }
      }
      return true;
    }

    /** {@code M}/{@code N}/{@code C}/{@code L}, plus {@code A} which is ignored (§4.4). */
    private boolean assignDetailField(char letter, String value) {
      switch (letter) {
        case 'M' -> memo = value;
        case 'N' -> referenceNumber = value;
        case 'C' -> clearedCode = value;
        case 'L' -> targetText = value;
        case 'A' -> {
          // payee mailing address — ignored; the payee-name parser is the source
        }
        default -> {
          return false;
        }
      }
      return true;
    }

    /** {@code S}/{@code E}/{@code $} — one split leg's three lines, in source order (§7). */
    private boolean assignSplitField(char letter, String value, String fieldLine) {
      switch (letter) {
        case 'S' -> splitLegs.add(new SplitLeg(value));
        case 'E' -> openLeg(fieldLine).memo = value;
        case '$' -> openLeg(fieldLine).amountText = value;
        default -> {
          return false;
        }
      }
      return true;
    }

    private SplitLeg openLeg(String fieldLine) {
      if (splitLegs.isEmpty()) {
        throw new QifRejectedException(
            "QIF split field \"" + fieldLine + "\" has no preceding S (category) line.");
      }
      return splitLegs.get(splitLegs.size() - 1);
    }

    private RawRecord build() {
      return new RawRecord(
          rawDate,
          amountText,
          duplicateAmountText,
          payeeText,
          memo,
          referenceNumber,
          clearedCode,
          targetText,
          List.copyOf(splitLegs));
    }
  }
}
