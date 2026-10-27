package volkovandr.hauptbuch.statements;

import dev.toonformat.jtoon.JToon;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The AI-free parser of a PDF (rules) profile (statements.md §3.2a): the statement's text and the
 * profile's parse rules in, the TOON body {@link ToonStatementDecoder} reads out — so the rest of
 * the PDF flow (the editable raw response, Re-seed, the line grid) does not know which parser ran.
 *
 * <p>Every rule matches one line at a time ({@code find()} semantics). Header rules read the full
 * text; remove rules then cut whole lines, in list order ({@link ParseRuleRemoval}); on each
 * remaining line set rules are tried before booking rules, and the first match consumes it. A line
 * nothing consumes is a continuation line, appended to the description of the booking the profile's
 * continuation setting names.
 */
@Component
class ParseRuleEngine {

  private static final String STATEMENT_KEY = "statement";
  private static final String LINES_KEY = "lines";
  private static final String BOOKING_DATE = "bookingDate";
  private static final String VALUE_DATE = "valueDate";
  private static final String PERIOD_START = "periodStart";

  /** Why this one rule cannot run, or empty when it can — the editor's per-rule check. */
  Optional<String> problem(ParseRule rule) {
    return ParseRuleValidation.problem(rule);
  }

  /** Every problem of the profile's rules and settings; empty when it can run and be saved. */
  List<String> problems(List<ParseRule> rules, ParseRuleSettings settings) {
    return ParseRuleValidation.problems(rules, settings);
  }

  /** The problems of the profile as a whole, not of one rule — the editor's live summary. */
  List<String> profileProblems(List<ParseRule> rules, ParseRuleSettings settings) {
    return ParseRuleValidation.profileProblems(rules, settings);
  }

  /**
   * Run the rules over the text.
   *
   * @return the TOON body: the {@code statement} header and the {@code lines} table, in the order
   *     the bookings were printed
   * @throws StatementFormatException when the rules or settings have a problem
   */
  String toToon(String text, List<ParseRule> rules, ParseRuleSettings settings) {
    List<String> problems = problems(rules, settings);
    if (!problems.isEmpty()) {
      throw new StatementFormatException(String.join(" ", problems));
    }
    List<String> lines = new ArrayList<>(text.lines().toList());
    Map<String, String> header = header(lines, rules);
    ParseRuleRemoval.apply(lines, rules);
    List<ParseRuleBooking> bookings = bookings(lines, rules, settings);

    DateTimeFormatter dateFormat = ParseRuleValues.dateFormat(settings.dateFormat());
    Map<String, Object> root = new LinkedHashMap<>();
    root.put(STATEMENT_KEY, headerRow(header, settings, dateFormat));
    root.put(LINES_KEY, bookings.stream().map(b -> lineRow(b, settings, dateFormat)).toList());
    return JToon.encode(root);
  }

  /** The header fields, each from the first line of the text a header rule reads it from. */
  private static Map<String, String> header(List<String> lines, List<ParseRule> rules) {
    List<Pattern> patterns = patterns(rules, ParseRule.HEADER);
    Map<String, String> header = new HashMap<>();
    for (String line : lines) {
      for (Pattern pattern : patterns) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
          captures(matcher).forEach(header::putIfAbsent);
        }
      }
    }
    return header;
  }

  // AvoidInstantiatingObjectsInLoops: one booking per booking line is the result being built.
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  private static List<ParseRuleBooking> bookings(
      List<String> lines, List<ParseRule> rules, ParseRuleSettings settings) {
    List<Pattern> setPatterns = patterns(rules, ParseRule.SET);
    List<Pattern> bookingPatterns = patterns(rules, ParseRule.BOOKING);
    String continuation = settings.continuation();
    Map<String, String> current = new HashMap<>();
    List<ParseRuleBooking> bookings = new ArrayList<>();
    List<String> waiting = new ArrayList<>();
    for (String line : lines) {
      if (line.isBlank()) {
        continue; // a blank line is neither a booking nor part of one
      }
      Optional<Matcher> set = firstMatch(setPatterns, line);
      if (set.isPresent()) {
        current.putAll(captures(set.get()));
        continue;
      }
      Optional<Matcher> booking = firstMatch(bookingPatterns, line);
      if (booking.isPresent()) {
        ParseRuleBooking started =
            new ParseRuleBooking(current, captures(booking.get()), settings.smartSpaceWidth());
        waiting.forEach(started::append);
        waiting.clear();
        bookings.add(started);
      } else if (ParseRuleSettings.CONTINUATION_PREVIOUS.equals(continuation)
          && !bookings.isEmpty()) {
        bookings.getLast().append(line);
      } else if (ParseRuleSettings.CONTINUATION_NEXT.equals(continuation)) {
        waiting.add(line);
      }
    }
    return bookings;
  }

  private static Map<String, Object> headerRow(
      Map<String, String> header, ParseRuleSettings settings, DateTimeFormatter dateFormat) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (String field : ParseRule.HEADER_FIELDS) {
      String raw = header.get(field);
      String value = raw == null ? null : ParseRuleValues.convert(field, raw, settings, dateFormat);
      if (value != null && PERIOD_START.equals(field) && settings.periodStartPlusOne()) {
        value = ParseRuleValues.nextDay(value);
      }
      row.put(field, value);
    }
    return row;
  }

  private static Map<String, Object> lineRow(
      ParseRuleBooking booking, ParseRuleSettings settings, DateTimeFormatter dateFormat) {
    Map<String, String> fields = booking.rawFields();
    fields.putIfAbsent(VALUE_DATE, fields.get(BOOKING_DATE));
    Map<String, Object> row = new LinkedHashMap<>();
    for (String field : ParseRule.LINE_FIELDS) {
      String raw = fields.get(field);
      row.put(
          field, raw == null ? null : ParseRuleValues.convert(field, raw, settings, dateFormat));
    }
    return row;
  }

  /** The named groups the matcher captured; a group that did not take part is left out. */
  private static Map<String, String> captures(Matcher matcher) {
    Map<String, String> captured = new HashMap<>();
    for (String group : matcher.pattern().namedGroups().keySet()) {
      String value = matcher.group(group);
      if (value != null) {
        captured.put(group, value);
      }
    }
    return captured;
  }

  private static List<Pattern> patterns(List<ParseRule> rules, String kind) {
    return rules.stream()
        .filter(rule -> kind.equals(rule.kind()))
        .map(rule -> Pattern.compile(rule.pattern()))
        .toList();
  }

  private static Optional<Matcher> firstMatch(List<Pattern> patterns, String line) {
    for (Pattern pattern : patterns) {
      Matcher matcher = pattern.matcher(line);
      if (matcher.find()) {
        return Optional.of(matcher);
      }
    }
    return Optional.empty();
  }
}
