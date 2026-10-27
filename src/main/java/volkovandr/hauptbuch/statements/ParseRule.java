package volkovandr.hauptbuch.statements;

import java.util.List;

/**
 * One parse rule of a PDF (rules) profile (statements.md §3.2a): a regex matched against one line
 * of the statement text at a time, whose named groups set fields from a fixed vocabulary.
 *
 * @param kind one of the kind constants below
 * @param pattern the regex; for a {@code remove_between} rule, A
 * @param patternEnd {@code remove_between} only: B, the line that ends the cut; else null
 */
// A rule is its three components plus the kind and field vocabulary they are checked against.
@SuppressWarnings("PMD.DataClass")
public record ParseRule(String kind, String pattern, String patternEnd) {

  /** Run over the full text before anything is removed; sets header fields, first match wins. */
  public static final String HEADER = "header";

  /** Cut from the start of the text to the first line matching the pattern, inclusive. */
  public static final String REMOVE_BEFORE = "remove_before";

  /** Cut from the last line matching the pattern to the end of the text, inclusive. */
  public static final String REMOVE_FROM = "remove_from";

  /** Cut every run of lines from one matching the pattern to the next matching the end. */
  public static final String REMOVE_BETWEEN = "remove_between";

  /** Its line fields hold for every following booking until the next set line changes them. */
  public static final String SET = "set";

  /** A matching line starts a booking; its own fields win over the set values. */
  public static final String BOOKING = "booking";

  /** Every kind, in the order the editor offers them. */
  public static final List<String> KINDS =
      List.of(HEADER, REMOVE_BEFORE, REMOVE_FROM, REMOVE_BETWEEN, SET, BOOKING);

  /** The fields a header rule may set, in the order the TOON header lists them. */
  public static final List<String> HEADER_FIELDS =
      List.of("periodStart", "periodEnd", "openingBalance", "closingBalance");

  /** The fields a set or booking rule may set, in the order the TOON lines table lists them. */
  public static final List<String> LINE_FIELDS =
      List.of(
          "bookingDate",
          "valueDate",
          "amount",
          "counterparty",
          "description",
          "bankCategory",
          "originalAmount",
          "originalCurrency",
          "originalRate");
}
