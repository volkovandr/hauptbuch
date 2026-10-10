package volkovandr.hauptbuch.statements;

import dev.toonformat.jtoon.JToon;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.statements.ParsedStatement.ParsedLine;

/**
 * Decodes the model's raw TOON body into a {@link ParsedStatement}, leniently: an absent or blank
 * cell is null, a header value that will not parse is dropped, and a line whose date or amount will
 * not parse is kept with a {@code problem} (like a CSV row the profile cannot read) so the operator
 * sees and fixes it. A body jtoon cannot parse at all yields empty — the statement keeps it in
 * {@code parse_raw} and fails.
 */
@Component
class ToonStatementDecoder {

  private static final String CELL_SEPARATOR = " | ";

  /** Decode the raw TOON body, or empty when it is blank or cannot be parsed. */
  // AvoidCatchingGenericException: any failure decoding an untrusted model body must yield empty
  // (the statement keeps the raw text and fails) rather than propagate — that is the point.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  Optional<ParsedStatement> decode(String rawToon) {
    if (rawToon == null || rawToon.isBlank()) {
      return Optional.empty();
    }
    try {
      Object tree = JToon.decode(unfence(rawToon));
      if (!(tree instanceof Map<?, ?> root)) {
        return Optional.empty();
      }
      Map<?, ?> header = root.get("statement") instanceof Map<?, ?> m ? m : Map.of();
      return Optional.of(
          new ParsedStatement(
              date(header.get("periodStart")),
              date(header.get("periodEnd")),
              num(header.get("openingBalance")),
              num(header.get("closingBalance")),
              lines(root.get("lines"))));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static List<ParsedLine> lines(Object raw) {
    List<ParsedLine> parsed = new ArrayList<>();
    if (raw instanceof List<?> rows) {
      for (Object row : rows) {
        if (row instanceof Map<?, ?> cells) {
          parsed.add(line(parsed.size(), cells));
        }
      }
    }
    return parsed;
  }

  private static ParsedLine line(int sortOrder, Map<?, ?> cells) {
    LocalDate booking = date(cells.get("bookingDate"));
    BigDecimal amount = num(cells.get("amount"));
    String problem = null;
    if (booking == null) {
      problem = "The booking date could not be read.";
    } else if (amount == null) {
      problem = "The amount could not be read.";
    }
    StatementLine line =
        new StatementLine(
            null,
            sortOrder,
            booking,
            date(cells.get("valueDate")),
            amount,
            str(cells.get("counterparty")),
            str(cells.get("description")),
            str(cells.get("bankCategory")),
            rawText(cells),
            problem);
    String currency = str(cells.get("originalCurrency"));
    return new ParsedLine(
        line,
        num(cells.get("originalAmount")),
        currency == null ? null : currency.toUpperCase(Locale.ROOT),
        num(cells.get("originalRate")));
  }

  /** The row as the model returned it, for the operator to compare against the PDF. */
  private static String rawText(Map<?, ?> cells) {
    return cells.values().stream()
        .map(ToonStatementDecoder::str)
        .map(cell -> cell == null ? "" : cell)
        .collect(Collectors.joining(CELL_SEPARATOR));
  }

  private static String str(Object value) {
    if (value == null || value instanceof Map<?, ?>) {
      return null;
    }
    String text = value.toString().strip();
    return text.isEmpty() ? null : text;
  }

  private static BigDecimal num(Object value) {
    String text = str(value);
    if (text == null) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static LocalDate date(Object value) {
    String text = str(value);
    if (text == null) {
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** Strip a leading/trailing Markdown code fence the model may have wrapped the TOON in. */
  private static String unfence(String body) {
    String trimmed = body.strip();
    if (!trimmed.startsWith("```")) {
      return trimmed;
    }
    int firstNewline = trimmed.indexOf('\n');
    if (firstNewline < 0) {
      return trimmed;
    }
    String withoutOpen = trimmed.substring(firstNewline + 1);
    int lastFence = withoutOpen.lastIndexOf("```");
    return (lastFence < 0 ? withoutOpen : withoutOpen.substring(0, lastFence)).strip();
  }
}
