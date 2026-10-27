package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.statements.repository.StatementProfileRepository;
import volkovandr.hauptbuch.statements.repository.StatementProfileRuleRepository;

/**
 * Integration tier (CLAUDE.md §6): V40 applies, a PDF (rules) profile's {@code rules_} settings
 * round-trip on its row, and its parse rules round-trip in order and are replaced as a whole. Each
 * test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementProfileRuleRepositoryIntegrationTest {

  private static final ParseRule HEADER =
      new ParseRule(ParseRule.HEADER, "Alter Kontostand vom (?<periodStart>\\S+)", null);
  private static final ParseRule BETWEEN =
      new ParseRule(ParseRule.REMOVE_BETWEEN, "^Folgeseite", "^Angaben zu den");
  private static final ParseRule BOOKING =
      new ParseRule(ParseRule.BOOKING, "^(?<counterparty>.*) (?<amount>[\\d.]+,\\d\\d-?)$", null);

  @Autowired StatementProfileRepository profiles;
  @Autowired StatementProfileRuleRepository rules;

  private long profileId;

  @BeforeEach
  void seed() {
    profileId = profiles.insert(rulesProfile("BankBbb rules"));
  }

  private static StatementProfile rulesProfile(String name) {
    StatementProfile blank = StatementProfile.blankPdfRules();
    return new StatementProfile(
        null,
        name,
        blank.format(),
        10,
        3,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        " ",
        ",",
        ParseRuleSettings.MINUS_LEADING,
        "dd.MM.yyyy",
        ParseRuleSettings.CONTINUATION_NEXT,
        42,
        true,
        null);
  }

  @Test
  void rulesSettingsRoundTripOnTheProfile() {
    StatementProfile read = profiles.findById(profileId).orElseThrow();

    assertThat(read)
        .usingRecursiveComparison()
        .ignoringFields("statementProfileId")
        .isEqualTo(rulesProfile("BankBbb rules"));
    assertThat(read.usesParseRules()).isTrue();
  }

  @Test
  void rulesRoundTripInTheirOrder() {
    rules.replace(profileId, List.of(BOOKING, HEADER, BETWEEN));

    assertThat(rules.findByProfile(profileId)).containsExactly(BOOKING, HEADER, BETWEEN);
  }

  @Test
  void replaceDropsTheRulesItDoesNotRepeat() {
    rules.replace(profileId, List.of(HEADER, BETWEEN, BOOKING));

    rules.replace(profileId, List.of(BOOKING));

    assertThat(rules.findByProfile(profileId)).containsExactly(BOOKING);
  }

  @Test
  void rulesBelongToTheirOwnProfile() {
    long otherId = profiles.insert(rulesProfile("BankCcc rules"));
    rules.replace(profileId, List.of(HEADER, BOOKING));

    rules.replace(otherId, List.of(BETWEEN));

    assertThat(rules.findByProfile(profileId)).containsExactly(HEADER, BOOKING);
    assertThat(rules.findByProfile(otherId)).containsExactly(BETWEEN);
  }

  @Test
  void schemaRefusesUnknownRuleKind() {
    List<ParseRule> unknown = List.of(new ParseRule("guess", "^x$", null));

    assertThatThrownBy(() -> rules.replace(profileId, unknown))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
