package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Unit tier: the parse rules as the profile editor posts them (statements.md §3.2a) — read row by
 * row without splitting a regex at its commas — and the add / remove / move row buttons.
 */
class ParseRuleFormTest {

  private static final ParseRule FIRST = new ParseRule(ParseRule.HEADER, "^A$", null);
  private static final ParseRule SECOND = new ParseRule(ParseRule.SET, "^B$", null);
  private static final ParseRule THIRD = new ParseRule(ParseRule.BOOKING, "^C$", null);
  private static final List<ParseRule> RULES = List.of(FIRST, SECOND, THIRD);

  @Test
  void readsRowsWithoutSplittingRegexAtCommas() {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    params.add("ruleKind", ParseRule.BOOKING);
    params.add("rulePattern", "^(?<amount>[\\d.]+,\\d\\d-?)$");
    params.add("rulePatternEnd", "");

    assertThat(ParseRuleForm.rulesOf(params))
        .containsExactly(new ParseRule(ParseRule.BOOKING, "^(?<amount>[\\d.]+,\\d\\d-?)$", ""));
  }

  @Test
  void missingFieldsAreEmptyAndNoKindsMeansNoRules() {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    params.add("ruleKind", ParseRule.SET);

    assertThat(ParseRuleForm.rulesOf(params)).containsExactly(new ParseRule(ParseRule.SET, "", ""));
    assertThat(ParseRuleForm.rulesOf(new LinkedMultiValueMap<>())).isEmpty();
  }

  @Test
  void addAppendsEmptyBookingRule() {
    assertThat(ParseRuleForm.edited(RULES, "add"))
        .containsExactly(FIRST, SECOND, THIRD, new ParseRule(ParseRule.BOOKING, "", null));
  }

  @Test
  void removesRow() {
    assertThat(ParseRuleForm.edited(RULES, "remove-1")).containsExactly(FIRST, THIRD);
  }

  @Test
  void movesRowUpAndDown() {
    assertThat(ParseRuleForm.edited(RULES, "up-1")).containsExactly(SECOND, FIRST, THIRD);
    assertThat(ParseRuleForm.edited(RULES, "down-1")).containsExactly(FIRST, THIRD, SECOND);
  }

  @Test
  void edgeAndUnknownEditsChangeNothing() {
    assertThat(ParseRuleForm.edited(RULES, "up-0")).isEqualTo(RULES);
    assertThat(ParseRuleForm.edited(RULES, "down-2")).isEqualTo(RULES);
    assertThat(ParseRuleForm.edited(RULES, "remove-9")).isEqualTo(RULES);
    assertThat(ParseRuleForm.edited(RULES, "remove-x")).isEqualTo(RULES);
    assertThat(ParseRuleForm.edited(RULES, "shuffle")).isEqualTo(RULES);
  }
}
