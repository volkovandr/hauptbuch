package volkovandr.hauptbuch.statements;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pre-masks a statement's extracted text before the operator sees it (statements.md §3.2): the
 * operator's own IBANs and account numbers, any other IBAN-shaped string, and a BIC that a {@code
 * BIC} or {@code SWIFT} label introduces. Counterparty names stay — matching needs them. The
 * masking is a starting point the operator can undo by editing, not a guarantee.
 *
 * <p>A bare eight-letter word is as BIC-shaped as a BIC, so an unlabelled one is left alone rather
 * than wrecking every capitalised counterparty name.
 */
final class StatementTextMasker {

  static final String OWN_ACCOUNT = "[ACCOUNT]";
  static final String IBAN = "[IBAN]";
  static final String BIC = "[BIC]";

  private static final Pattern IBAN_SHAPE =
      Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,3})?\\b");
  private static final Pattern LABELLED_BIC =
      Pattern.compile(
          "\\b((?i:BIC|SWIFT)(?:[-/ ]?(?i:code|adresse|address))?\\s*:?\\s*)"
              + "[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}(?:[A-Z0-9]{3})?\\b");

  private StatementTextMasker() {}

  /**
   * The text with the operator's own identifiers, IBAN-shaped strings and labelled BICs replaced by
   * placeholders.
   *
   * @param ownIdentifiers the IBANs and account numbers on the operator's accounts, as typed
   */
  static String mask(String text, List<String> ownIdentifiers) {
    String masked = text;
    List<String> longestFirst =
        ownIdentifiers.stream()
            .map(id -> id.replaceAll("\\s+", ""))
            .filter(id -> !id.isEmpty())
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    for (String id : longestFirst) {
      masked = ownPattern(id).matcher(masked).replaceAll(Matcher.quoteReplacement(OWN_ACCOUNT));
    }
    masked = IBAN_SHAPE.matcher(masked).replaceAll(Matcher.quoteReplacement(IBAN));
    return LABELLED_BIC.matcher(masked).replaceAll("$1" + Matcher.quoteReplacement(BIC));
  }

  /**
   * The identifier with optional blanks (not line breaks) between any two characters, ignoring
   * case, and not part of a longer run of letters or digits.
   */
  private static Pattern ownPattern(String compactId) {
    String spaced =
        compactId
            .chars()
            .mapToObj(c -> Pattern.quote(String.valueOf((char) c)))
            .collect(Collectors.joining("[ \\t]*"));
    return Pattern.compile(
        "(?<![A-Za-z0-9])" + spaced + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
  }
}
