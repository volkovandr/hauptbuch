package volkovandr.hauptbuch.statements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;

/**
 * The parse rules as the profile editor posts and shows them (statements.md §3.2a): one row per
 * rule, as the parallel {@code ruleKind} / {@code rulePattern} / {@code rulePatternEnd} fields, and
 * the edits the row buttons ask for. The fields are read raw from the request — a regex such as
 * {@code [\d.]+,\d\d} must never be split at its commas the way a bound list would be.
 */
final class ParseRuleForm {

  static final String RULES = "rules";
  static final String RULE_PROBLEMS = "ruleProblems";
  static final String PROFILE_PROBLEMS = "profileProblems";

  private static final String ADD = "add";
  private static final String REMOVE = "remove-";
  private static final String UP = "up-";
  private static final String DOWN = "down-";

  private ParseRuleForm() {}

  /** The rules in the posted order; a missing field is empty. */
  static List<ParseRule> rulesOf(MultiValueMap<String, String> params) {
    List<String> kinds = params.getOrDefault("ruleKind", List.of());
    List<String> patterns = params.getOrDefault("rulePattern", List.of());
    List<String> ends = params.getOrDefault("rulePatternEnd", List.of());
    List<ParseRule> rules = new ArrayList<>();
    for (int i = 0; i < kinds.size(); i++) {
      rules.add(new ParseRule(kinds.get(i), at(patterns, i), at(ends, i)));
    }
    return rules;
  }

  /**
   * {@code rules} after the row button {@code op}: {@code add} appends an empty booking rule;
   * {@code remove-N}, {@code up-N} and {@code down-N} act on row N. Anything else changes nothing.
   */
  static List<ParseRule> edited(List<ParseRule> rules, String op) {
    List<ParseRule> edited = new ArrayList<>(rules);
    if (ADD.equals(op)) {
      edited.add(new ParseRule(ParseRule.BOOKING, "", null));
    } else if (op.startsWith(REMOVE)) {
      int row = row(op, REMOVE, edited);
      if (row >= 0) {
        edited.remove(row);
      }
    } else if (op.startsWith(UP)) {
      int row = row(op, UP, edited);
      if (row > 0) {
        Collections.swap(edited, row, row - 1);
      }
    } else if (op.startsWith(DOWN)) {
      int row = row(op, DOWN, edited);
      if (row >= 0 && row < edited.size() - 1) {
        Collections.swap(edited, row, row + 1);
      }
    }
    return edited;
  }

  /**
   * Put the rules and their problems on the model: one entry per rule (null when it is fine) and
   * the problems of the profile as a whole.
   */
  static void addTo(
      Model model, List<ParseRule> rules, ParseRuleSettings settings, ParseRuleEngine engine) {
    model.addAttribute(RULES, rules);
    model.addAttribute(
        RULE_PROBLEMS, rules.stream().map(rule -> engine.problem(rule).orElse(null)).toList());
    model.addAttribute(PROFILE_PROBLEMS, engine.profileProblems(rules, settings));
  }

  private static String at(List<String> values, int i) {
    return i < values.size() ? values.get(i) : "";
  }

  /** The row number after {@code prefix}, or -1 when it is not a row of {@code rules}. */
  private static int row(String op, String prefix, List<ParseRule> rules) {
    try {
      int row = Integer.parseInt(op.substring(prefix.length()));
      return row < rules.size() ? row : -1;
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
