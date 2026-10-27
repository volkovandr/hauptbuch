package volkovandr.hauptbuch.statements;

/**
 * How a PDF (rules) profile reads the values its rules capture, and where continuation lines go
 * (statements.md §3.2a). Independent of the CSV dialect even where the meaning is the same.
 *
 * @param thousandsSeparator stripped from every amount; empty for none
 * @param decimalSeparator the one character before the cents
 * @param minusPosition {@link #MINUS_LEADING} or {@link #MINUS_TRAILING}; no minus is money in
 * @param dateFormat a {@code java.time} pattern such as {@code dd.MM.yyyy}, for every date field
 * @param continuation where a line no rule consumes goes: {@link #CONTINUATION_PREVIOUS}, {@link
 *     #CONTINUATION_NEXT} or {@link #CONTINUATION_OFF}
 * @param smartSpaceWidth the PDF's description column width: a continuation line exactly this long
 *     joins the next with no space; null for always a space
 * @param periodStartPlusOne whether the period starts the day after the date the header rule reads
 *     (a bank that prints the previous statement's closing balance and date)
 */
// The settings are their components plus the values the choice components take.
@SuppressWarnings("PMD.DataClass")
public record ParseRuleSettings(
    String thousandsSeparator,
    String decimalSeparator,
    String minusPosition,
    String dateFormat,
    String continuation,
    Integer smartSpaceWidth,
    boolean periodStartPlusOne) {

  /** The minus sign is printed before the amount: {@code -6,80}. */
  public static final String MINUS_LEADING = "leading";

  /** The minus sign is printed after the amount: {@code 6,80-}. */
  public static final String MINUS_TRAILING = "trailing";

  /** A continuation line is appended to the booking above it. */
  public static final String CONTINUATION_PREVIOUS = "previous";

  /** A continuation line is appended to the booking below it. */
  public static final String CONTINUATION_NEXT = "next";

  /** A continuation line is dropped. */
  public static final String CONTINUATION_OFF = "off";
}
