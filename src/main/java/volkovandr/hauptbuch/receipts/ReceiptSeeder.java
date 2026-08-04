package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.PayingAccountDetector;
import volkovandr.hauptbuch.categories.AiVocabularyService;
import volkovandr.hauptbuch.categories.TagService;
import volkovandr.hauptbuch.debts.PersonMatch;
import volkovandr.hauptbuch.debts.PersonService;

/**
 * Turns a decoded {@link ParsedReceipt} into a {@link SeededReceipt} (stage 9e): the denormalised
 * header and the draft lines, with every AI echo resolved against the live entities. Lenient by
 * design (data-model §13.1/§13.2) — a header field that fails to parse into its type stays null, an
 * unresolved category seeds uncategorised, and unresolved tag/beneficiary echoes are silently
 * dropped (suggestions, never creations). Pure orchestration over the resolver services, so it is
 * unit-tested with them mocked.
 *
 * <p>ARCH-08: no ledger content is sent to resolve anything — the AI's answers are matched here,
 * against {@code categories} (leaves-only, non-creating), {@code categories}' non-creating tag
 * lookup, {@code debts}' exact person match, and {@code accounts}' §13.4 detection.
 */
@Component
public class ReceiptSeeder {

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");

  private final AiVocabularyService aiVocabularyService;
  private final TagService tagService;
  private final PersonService personService;
  private final PayingAccountDetector payingAccountDetector;

  ReceiptSeeder(
      AiVocabularyService aiVocabularyService,
      TagService tagService,
      PersonService personService,
      PayingAccountDetector payingAccountDetector) {
    this.aiVocabularyService = aiVocabularyService;
    this.tagService = tagService;
    this.personService = personService;
    this.payingAccountDetector = payingAccountDetector;
  }

  /**
   * Seed the header and lines from a decoded parse. The header is built first because its parsed
   * currency is what the lines' transfer-target detection resolves cash against (§13.4).
   */
  public SeededReceipt seed(ParsedReceipt parsed) {
    ParsedHeader header = header(parsed);
    return new SeededReceipt(header, lines(parsed, header.currencyCode()));
  }

  /**
   * The denormalised header. Built with sequential {@code if}-block assignments (not a wall of
   * inline ternaries) so a null merchant/transaction is one branch each, not a multiplicative
   * explosion of paths.
   */
  private ParsedHeader header(ParsedReceipt parsed) {
    String merchantText = null;
    String merchantCity = null;
    String merchantCountry = null;
    ParsedMerchant merchant = parsed.merchant();
    if (merchant != null) {
      merchantText = merchant.name();
      merchantCity = merchant.city();
      merchantCountry = merchant.country();
    }

    LocalDate date = null;
    LocalTime time = null;
    String receiptNumber = null;
    BigDecimal total = null;
    String currency = null;
    Long payingAccount = null;
    ParsedTransaction txn = parsed.transaction();
    if (txn != null) {
      date = parseDate(txn.date());
      time = parseTime(txn.time());
      receiptNumber = txn.receiptNumber();
      total = txn.totalAmount();
      currency = txn.currency();
      payingAccount = detected(txn.account(), currency);
    }
    return new ParsedHeader(
        merchantText,
        merchantCity,
        merchantCountry,
        date,
        time,
        receiptNumber,
        total,
        currency,
        payingAccount);
  }

  private List<ReceiptLineDraft> lines(ParsedReceipt parsed, String currency) {
    List<ReceiptLineDraft> drafts = new ArrayList<>();
    List<ParsedItem> items = parsed.items();
    for (int sortOrder = 0; sortOrder < items.size(); sortOrder++) {
      drafts.add(lineOf(items.get(sortOrder), sortOrder, currency));
    }
    return drafts;
  }

  private ReceiptLineDraft lineOf(ParsedItem item, int sortOrder, String currency) {
    return new ReceiptLineDraft(
        describe(item),
        item.totalPrice() == null ? BigDecimal.ZERO : item.totalPrice(),
        targetAccount(item, currency),
        beneficiary(item),
        null,
        sortOrder,
        tags(item),
        aiTargetText(item));
  }

  /**
   * The AI's raw target term, kept for the post-process ghost hint / provenance tooltip (data-model
   * §13.2). A transfer signal renders as {@code transfer: cash} / {@code transfer: card •1234} —
   * the text that marks a targetless transfer line for what it is even when its account did not
   * resolve; otherwise the echoed category path verbatim (resolved or not); null when the AI named
   * neither.
   */
  private static String aiTargetText(ParsedItem item) {
    if (item.transfer() != null && !item.transfer().isBlank()) {
      String signal = item.transfer().strip();
      return "cash".equalsIgnoreCase(signal) ? "transfer: cash" : "transfer: card •" + signal;
    }
    if (item.category() != null && !item.category().isBlank()) {
      return item.category().strip();
    }
    return null;
  }

  /** The item name, with a quantity greater than one folded in as {@code N× …}. */
  private static String describe(ParsedItem item) {
    String name = item.name() == null ? "" : item.name().strip();
    BigDecimal qty = item.quantity();
    if (qty != null && qty.compareTo(BigDecimal.ONE) > 0) {
      return qty.stripTrailingZeros().toPlainString() + "× " + name;
    }
    return name;
  }

  /**
   * The line's target account: a resolved transfer target when the item carries a transfer signal
   * (§13.4) — even an unresolved transfer stays targetless rather than falling through to a
   * category — otherwise the resolved category leaf, or null (uncategorised).
   */
  private Long targetAccount(ParsedItem item, String currency) {
    if (item.transfer() != null && !item.transfer().isBlank()) {
      return detected(item.transfer(), currency);
    }
    OptionalLong leaf = aiVocabularyService.resolveTerm(item.category());
    return leaf.isPresent() ? leaf.getAsLong() : null;
  }

  /**
   * The account a payment signal resolves to, or null when nothing matched — the paying account for
   * the header, a transfer target for a withdrawal line (§13.4). Null is a real answer: the
   * operator picks on the post-process screen rather than having one guessed for them.
   */
  private Long detected(String signal, String currency) {
    OptionalLong account = payingAccountDetector.detect(signal, currency);
    return account.isPresent() ? account.getAsLong() : null;
  }

  /** The beneficiary person for a person-debt leg, resolved by exact live name, or null. */
  private Long beneficiary(ParsedItem item) {
    String name = item.beneficiary();
    if (name == null || name.isBlank()) {
      return null;
    }
    return personService.matchExact(name) instanceof PersonMatch.Live live
        ? live.person().personId()
        : null;
  }

  /**
   * The resolved leaf tag ids for a line: each {@code Parent:Child} echo, non-creating, or dropped.
   */
  private List<Long> tags(ParsedItem item) {
    if (item.tags() == null || item.tags().isBlank()) {
      return List.of();
    }
    List<Long> ids = new ArrayList<>();
    for (String raw : item.tags().split("[,;]")) {
      String chip = raw.strip();
      if (!chip.isEmpty()) {
        tagService.resolveExistingChip(chip).ifPresent(resolved -> ids.add(resolved.tagId()));
      }
    }
    return ids;
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.strip());
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  private static LocalTime parseTime(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalTime.parse(raw.strip(), TIME);
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }
}
