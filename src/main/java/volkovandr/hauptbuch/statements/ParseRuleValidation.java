package volkovandr.hauptbuch.statements;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What stops a PDF (rules) profile from being run or saved (statements.md §3.2a): a rule that will
 * not compile, names a group outside the vocabulary or of the wrong kind, a {@code between} rule
 * without its end, no booking rule at all, and settings that cannot be read. Every message is
 * written for the screen.
 */
final class ParseRuleValidation {

  private static final Set<String> CONTINUATIONS =
      Set.of(
          ParseRuleSettings.CONTINUATION_PREVIOUS,
          ParseRuleSettings.CONTINUATION_NEXT,
          ParseRuleSettings.CONTINUATION_OFF);

  private static final List<String> DECIMAL_SEPARATORS = List.of(",", ".");

  private static final List<String> THOUSANDS_SEPARATORS = List.of("", ".", ",", "'", " ");

  private static final Set<String> MINUS_POSITIONS =
      Set.of(ParseRuleSettings.MINUS_LEADING, ParseRuleSettings.MINUS_TRAILING);

  private ParseRuleValidation() {}

  /** Why this one rule cannot run, or empty when it can. */
  static Optional<String> problem(ParseRule rule) {
    if (rule.kind() == null || !ParseRule.KINDS.contains(rule.kind())) {
      return Optional.of("Unknown rule kind '" + rule.kind() + "'.");
    }
    if (rule.pattern() == null || rule.pattern().isBlank()) {
      return Optional.of("The pattern is empty.");
    }
    Pattern pattern;
    try {
      pattern = Pattern.compile(rule.pattern());
    } catch (PatternSyntaxException e) {
      return Optional.of("Invalid regex: " + e.getDescription() + ".");
    }
    if (ParseRule.REMOVE_BETWEEN.equals(rule.kind())) {
      return endProblem(rule.patternEnd());
    }
    return groupProblem(rule.kind(), pattern);
  }

  /** Every problem of the profile's rules and settings, each rule's numbered from 1. */
  static List<String> problems(List<ParseRule> rules, ParseRuleSettings settings) {
    List<String> problems = new ArrayList<>();
    for (int i = 0; i < rules.size(); i++) {
      int position = i + 1;
      problem(rules.get(i)).ifPresent(p -> problems.add("Rule " + position + ": " + p));
    }
    problems.addAll(profileProblems(rules, settings));
    return problems;
  }

  /** The problems of the profile as a whole — no booking rule, the settings — not of one rule. */
  static List<String> profileProblems(List<ParseRule> rules, ParseRuleSettings settings) {
    List<String> problems = new ArrayList<>();
    if (rules.stream().noneMatch(rule -> ParseRule.BOOKING.equals(rule.kind()))) {
      problems.add("Add at least one booking rule.");
    }
    problems.addAll(settingsProblems(settings));
    return problems;
  }

  private static Optional<String> endProblem(String patternEnd) {
    if (patternEnd == null || patternEnd.isBlank()) {
      return Optional.of("A between rule needs an end pattern (B).");
    }
    try {
      Pattern.compile(patternEnd);
      return Optional.empty();
    } catch (PatternSyntaxException e) {
      return Optional.of("Invalid end regex: " + e.getDescription() + ".");
    }
  }

  private static Optional<String> groupProblem(String kind, Pattern pattern) {
    boolean header = ParseRule.HEADER.equals(kind);
    boolean line = ParseRule.SET.equals(kind) || ParseRule.BOOKING.equals(kind);
    List<String> allowed = header ? ParseRule.HEADER_FIELDS : ParseRule.LINE_FIELDS;
    if (!header && !line) {
      return Optional.empty(); // a remove rule's groups set nothing
    }
    return pattern.namedGroups().keySet().stream()
        .filter(group -> !allowed.contains(group))
        .sorted()
        .findFirst()
        .map(
            group ->
                "'"
                    + group
                    + "' is not a field this rule can set; use one of "
                    + String.join(", ", allowed)
                    + ".");
  }

  private static List<String> settingsProblems(ParseRuleSettings settings) {
    List<String> problems = new ArrayList<>(numberProblems(settings));
    dateFormatProblem(settings.dateFormat()).ifPresent(problems::add);
    if (!CONTINUATIONS.contains(settings.continuation())) {
      problems.add("Choose where continuation lines go.");
    }
    if (settings.smartSpaceWidth() != null && settings.smartSpaceWidth() < 1) {
      problems.add("The smart space width must be at least 1, or empty for off.");
    }
    return problems;
  }

  private static List<String> numberProblems(ParseRuleSettings settings) {
    List<String> problems = new ArrayList<>();
    String decimal = settings.decimalSeparator();
    String thousands = settings.thousandsSeparator() == null ? "" : settings.thousandsSeparator();
    if (!DECIMAL_SEPARATORS.contains(decimal)) {
      problems.add("The decimal separator must be , or .");
    }
    if (!THOUSANDS_SEPARATORS.contains(thousands)) {
      problems.add("The thousands separator must be . , ' or a space, or empty for none.");
    } else if (thousands.equals(decimal)) {
      problems.add("The thousands and decimal separators must differ.");
    }
    if (!MINUS_POSITIONS.contains(settings.minusPosition())) {
      problems.add("Choose where the minus sign is printed.");
    }
    return problems;
  }

  private static Optional<String> dateFormatProblem(String dateFormat) {
    if (dateFormat == null || dateFormat.isBlank()) {
      return Optional.of("The date format is empty.");
    }
    try {
      ParseRuleValues.dateFormat(dateFormat);
      return Optional.empty();
    } catch (IllegalArgumentException e) {
      return Optional.of("The date format '" + dateFormat + "' is not a valid pattern.");
    }
  }
}
