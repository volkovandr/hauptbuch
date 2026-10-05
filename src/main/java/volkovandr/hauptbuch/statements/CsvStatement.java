package volkovandr.hauptbuch.statements;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a profile makes of one CSV file.
 *
 * @param headers the column names found in the header row; empty for a file without one
 * @param lines the lines in file order, each with its problem when it could not be read
 * @param ibans the distinct values of the profile's IBAN column, for the account proposal
 */
public record CsvStatement(List<String> headers, List<StatementLine> lines, Set<String> ibans) {

  /** Keeps the record immutable: the lists and the set are copied in. */
  public CsvStatement {
    headers = List.copyOf(headers);
    lines = List.copyOf(lines);
    ibans = Set.copyOf(ibans);
  }

  /** The earliest booking date of the readable lines, or null when there is none. */
  LocalDate firstBooking() {
    return lines.stream()
        .map(StatementLine::bookingDate)
        .filter(Objects::nonNull)
        .min(LocalDate::compareTo)
        .orElse(null);
  }

  /** The latest booking date of the readable lines, or null when there is none. */
  LocalDate lastBooking() {
    return lines.stream()
        .map(StatementLine::bookingDate)
        .filter(Objects::nonNull)
        .max(LocalDate::compareTo)
        .orElse(null);
  }

  /** The problem text of each line that has one. */
  List<String> problems() {
    return lines.stream().map(StatementLine::problem).filter(Objects::nonNull).toList();
  }
}
