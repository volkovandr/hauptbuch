package volkovandr.hauptbuch.statements;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.statements.repository.StatementProfileRepository;
import volkovandr.hauptbuch.statements.repository.StatementProfileRuleRepository;

/**
 * Statement profiles (statements.md §3.1, §3.2a): validation on top of the plain repositories. A
 * profile is reference data, so this is the one place the generic create/update shape is right
 * (CLAUDE.md §1.7); what makes a profile <em>valid</em> lives in {@link
 * StatementCsvParser#validate} for a CSV profile and in {@link ParseRuleEngine#problems} for a PDF
 * (rules) profile, whose rules are saved with it.
 */
@Service
public class StatementProfileService {

  private static final Logger LOG = LoggerFactory.getLogger(StatementProfileService.class);

  private final StatementProfileRepository repository;
  private final StatementProfileRuleRepository ruleRepository;
  private final StatementCsvParser parser;
  private final ParseRuleEngine ruleEngine;

  StatementProfileService(
      StatementProfileRepository repository,
      StatementProfileRuleRepository ruleRepository,
      StatementCsvParser parser,
      ParseRuleEngine ruleEngine) {
    this.repository = repository;
    this.ruleRepository = ruleRepository;
    this.parser = parser;
    this.ruleEngine = ruleEngine;
  }

  /** The live profiles, by name. */
  public List<StatementProfile> live() {
    return repository.findLive();
  }

  /**
   * A live profile by id.
   *
   * @throws StatementFormatException when it does not exist or was deleted
   */
  public StatementProfile get(long statementProfileId) {
    return repository
        .findById(statementProfileId)
        .filter(profile -> profile.deletedAt() == null)
        .orElseThrow(
            () -> new StatementFormatException("That statement profile no longer exists."));
  }

  /**
   * A profile, live or soft-deleted — a statement keeps matching with the window it was read with.
   *
   * @throws StatementFormatException when it does not exist
   */
  public StatementProfile getIncludingDeleted(long statementProfileId) {
    return repository
        .findById(statementProfileId)
        .orElseThrow(
            () -> new StatementFormatException("That statement profile no longer exists."));
  }

  /** A PDF (rules) profile's parse rules, in order; none for any other profile. */
  public List<ParseRule> rules(long statementProfileId) {
    return ruleRepository.findByProfile(statementProfileId);
  }

  /** The name of a profile, live or soft-deleted — a statement keeps showing where it came from. */
  public String nameOf(long statementProfileId) {
    return repository
        .findById(statementProfileId)
        .map(StatementProfile::name)
        .orElse("(unknown profile)");
  }

  /**
   * Validate and save {@code submitted}: insert when it has no id, otherwise overwrite. A PDF
   * (rules) profile's rules replace the ones it had; any other profile ignores {@code rules}.
   *
   * @return the profile's id
   * @throws StatementFormatException naming what is wrong
   */
  @Transactional
  public long save(StatementProfile submitted, List<ParseRule> rules) {
    StatementProfile profile = normalised(submitted);
    List<ParseRule> normalisedRules = normalisedRules(rules);
    validate(profile, normalisedRules);
    long id;
    if (profile.statementProfileId() == null) {
      id = repository.insert(profile);
      LOG.info("Created statement profile {}", id);
    } else {
      id = profile.statementProfileId();
      if (repository.update(id, profile) == 0) {
        throw new StatementFormatException("That statement profile no longer exists.");
      }
    }
    if (profile.usesParseRules()) {
      ruleRepository.replace(id, normalisedRules);
    }
    return id;
  }

  /** Soft-delete a profile; statements read through it keep working. */
  public void delete(long statementProfileId) {
    if (repository.softDelete(statementProfileId) > 0) {
      LOG.info("Deleted statement profile {}", statementProfileId);
    }
  }

  /** Validate {@code submitted} and its {@code rules} as {@link #save} would, without writing. */
  void validate(StatementProfile submitted, List<ParseRule> rules) {
    if (submitted.name() == null || submitted.name().isBlank()) {
      throw new StatementFormatException("Give the profile a name.");
    }
    if (submitted.windowDaysBefore() < 0 || submitted.windowDaysAfter() < 0) {
      throw new StatementFormatException("The date window cannot be negative.");
    }
    if (submitted.usesParseRules()) {
      validateRules(submitted, rules);
    } else if (!submitted.isPdf()) {
      parser.validate(submitted);
    }
  }

  private void validateRules(StatementProfile submitted, List<ParseRule> rules) {
    List<String> problems = ruleEngine.problems(rules, submitted.rulesSettings());
    if (!problems.isEmpty()) {
      throw new StatementFormatException(String.join(" ", problems));
    }
  }

  /**
   * The rules as the form sent them, tidied: a pattern is kept exactly (a space in a regex
   * matters), and an end pattern is kept only on a {@code between} rule.
   */
  static List<ParseRule> normalisedRules(List<ParseRule> rules) {
    return rules.stream()
        .map(
            rule ->
                new ParseRule(
                    rule.kind(),
                    rule.pattern() == null ? "" : rule.pattern(),
                    ParseRule.REMOVE_BETWEEN.equals(rule.kind())
                        ? blankToNull(rule.patternEnd())
                        : null))
        .toList();
  }

  /** {@code submitted} with text trimmed, blank optional columns made null, and defaults filled. */
  static StatementProfile normalised(StatementProfile s) {
    if (s.usesParseRules()) {
      return normalisedRulesProfile(s);
    }
    if (s.isPdf()) {
      return new StatementProfile(
          s.statementProfileId(),
          s.name() == null ? "" : s.name().strip(),
          StatementProfile.FORMAT_PDF,
          s.windowDaysBefore(),
          s.windowDaysAfter(),
          blankToNull(s.aiNote()),
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
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
    return new StatementProfile(
        s.statementProfileId(),
        s.name() == null ? "" : s.name().strip(),
        StatementProfile.FORMAT_CSV,
        s.windowDaysBefore(),
        s.windowDaysAfter(),
        blankToNull(s.aiNote()),
        s.csvDelimiter(),
        s.csvQuote(),
        blankToNull(s.csvEncoding()) == null ? "UTF-8" : s.csvEncoding().strip(),
        Objects.requireNonNullElse(s.csvSkipRows(), 0),
        Boolean.TRUE.equals(s.csvHasHeader()),
        s.csvDecimalSeparator(),
        s.csvDateFormat() == null ? null : s.csvDateFormat().strip(),
        s.csvSignMode(),
        blankToNull(s.colBookingDate()),
        blankToNull(s.colValueDate()),
        blankToNull(s.colAmount()),
        blankToNull(s.colDebit()),
        blankToNull(s.colCredit()),
        blankToNull(s.colCurrency()),
        blankToNull(s.colCounterparty()),
        blankToNull(s.colDescription()),
        blankToNull(s.colBankCategory()),
        blankToNull(s.colIban()),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** A PDF (rules) profile keeps only its name, window and rules settings. */
  private static StatementProfile normalisedRulesProfile(StatementProfile s) {
    return new StatementProfile(
        s.statementProfileId(),
        s.name() == null ? "" : s.name().strip(),
        StatementProfile.FORMAT_PDF_RULES,
        s.windowDaysBefore(),
        s.windowDaysAfter(),
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
        Objects.requireNonNullElse(s.rulesThousandsSeparator(), ""),
        s.rulesDecimalSeparator(),
        s.rulesMinusPosition(),
        s.rulesDateFormat() == null ? null : s.rulesDateFormat().strip(),
        s.rulesContinuation(),
        s.rulesSmartSpaceWidth(),
        Boolean.TRUE.equals(s.rulesPeriodStartPlusOne()),
        null);
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }
}
