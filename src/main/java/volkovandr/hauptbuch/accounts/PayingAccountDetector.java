package volkovandr.hauptbuch.accounts;

import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * Resolves a parsed payment signal to a paying account (data-model §13.4). Its own small component
 * rather than a method on {@link AccountService}: the signal matching is a distinct concern from
 * account management, and the analyse worker uses it for both the paying account ({@code
 * transaction.account}) and per-item transfer targets, which share the signal vocabulary.
 *
 * <p>Two rules, in this order:
 *
 * <ol>
 *   <li><b>Labels.</b> Each account carries a comma-separated list of substrings identifying it in
 *       a payment line ({@code card, 1234, girocard}); the first that appears in the signal,
 *       case-insensitively, wins. The AI names the payment line freely — real parses read {@code
 *       card}, {@code XXXX1234}, {@code card XXXX1234} — so matching is substring-based rather than
 *       an exact card last-4, which a bare {@code card} could never satisfy.
 *   <li><b>Cash.</b> Failing that, a signal naming cash resolves to the cash account <em>of the
 *       receipt's currency</em>. Labels are tried first on purpose: {@code Barclaycard} and {@code
 *       Bargeldauszahlung} both contain {@code bar}, and the operator's explicit configuration must
 *       outrank the built-in vocabulary.
 * </ol>
 *
 * <p>Anything else stays empty and the operator picks. There is deliberately no default-account
 * fallback: an account guessed from nothing is indistinguishable, on the post-process screen, from
 * one the operator chose, and a wrong guess books real money.
 */
@Component
public class PayingAccountDetector {

  private final AccountRepository accountRepository;

  PayingAccountDetector(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  /**
   * Resolve the paying/transfer account for a parsed payment signal, or empty when nothing matches.
   *
   * @param signal what the AI called the payment line, verbatim
   * @param currencyCode the parsed receipt currency — raw model output, so normalised here rather
   *     than trusted; without it the cash rule cannot choose between per-currency cash accounts and
   *     does not fire
   */
  public OptionalLong detect(String signal, String currencyCode) {
    if (signal == null || signal.isBlank()) {
      return OptionalLong.empty();
    }
    String currency = normalisedCurrency(currencyCode);
    String lowerSignal = signal.toLowerCase(Locale.ROOT);
    List<AccountDetectionCandidate> candidates =
        accountRepository.findDetectionCandidates(currency);

    OptionalLong labelled = byLabel(candidates, lowerSignal);
    if (labelled.isPresent()) {
      return labelled;
    }
    return namesCash(lowerSignal) ? cashAccountIn(candidates, currency) : OptionalLong.empty();
  }

  /** Null for anything the parse left blank, so the cash rule can tell "no currency" apart. */
  private static String normalisedCurrency(String currencyCode) {
    if (currencyCode == null || currencyCode.isBlank()) {
      return null;
    }
    return currencyCode.strip().toUpperCase(Locale.ROOT);
  }

  /**
   * The first candidate carrying a label that appears in the signal — candidates in the
   * repository's documented order, labels within one account in the order the operator typed them.
   */
  private static OptionalLong byLabel(
      List<AccountDetectionCandidate> candidates, String lowerSignal) {
    return candidates.stream()
        .filter(candidate -> DetectionLabels.matches(candidate.detectionLabels(), lowerSignal))
        .mapToLong(AccountDetectionCandidate::accountId)
        .findFirst();
  }

  /** The built-in cash vocabulary: the English word and the German {@code Bar} a till prints. */
  private static boolean namesCash(String lowerSignal) {
    return lowerSignal.contains("cash") || lowerSignal.contains("bar");
  }

  /**
   * The cash account of the receipt's currency. Candidates are name-ordered, so a duplicate cash
   * marker — a misconfiguration, but not one worth refusing over — resolves to the first
   * alphabetically rather than at random.
   */
  private static OptionalLong cashAccountIn(
      List<AccountDetectionCandidate> candidates, String currency) {
    if (currency == null) {
      return OptionalLong.empty();
    }
    return candidates.stream()
        .filter(AccountDetectionCandidate::cashAccount)
        .filter(candidate -> currency.equals(candidate.currencyCode()))
        .mapToLong(AccountDetectionCandidate::accountId)
        .findFirst();
  }
}
