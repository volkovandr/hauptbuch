package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.ledger.AiSettings;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.statements.repository.ParseUsage;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * The AI call of a PDF statement (statements.md §3.2, slice e2): claim the statement, send exactly
 * its edited text with the parsing instructions in one synchronous call, then store the raw
 * response, the token usage and the frozen cost, and seed the lines and header — or land {@code
 * failed} with the error kept. The call runs outside any database transaction; the writes go
 * through {@link StatementParseResults}.
 */
@Service
public class StatementParseService {

  private static final Logger LOG = LoggerFactory.getLogger(StatementParseService.class);

  private final StatementRepository statementRepository;
  private final StatementProfileService profileService;
  private final StatementService statementService;
  private final StatementParser parser;
  private final StatementPromptBuilder promptBuilder;
  private final ToonStatementDecoder decoder;
  private final StatementParseResults results;
  private final SettingsService settingsService;
  private final StatementMatchRepository matchRepository;
  private final ParseRuleEngine ruleEngine;

  // ExcessiveParameterList: one collaborator per step of the two parse paths (AI call, rules).
  @SuppressWarnings("PMD.ExcessiveParameterList")
  StatementParseService(
      StatementRepository statementRepository,
      StatementProfileService profileService,
      StatementService statementService,
      StatementParser parser,
      StatementPromptBuilder promptBuilder,
      ToonStatementDecoder decoder,
      StatementParseResults results,
      SettingsService settingsService,
      StatementMatchRepository matchRepository,
      ParseRuleEngine ruleEngine) {
    this.statementRepository = statementRepository;
    this.profileService = profileService;
    this.statementService = statementService;
    this.parser = parser;
    this.promptBuilder = promptBuilder;
    this.decoder = decoder;
    this.results = results;
    this.settingsService = settingsService;
    this.matchRepository = matchRepository;
    this.ruleEngine = ruleEngine;
  }

  /**
   * Replace the statement's lines and header from the operator's edited response text, without
   * another API call (statements.md §3.2). Refused while any line is matched, and when the text
   * does not decode — then nothing changes.
   *
   * @throws StatementFormatException when a line is matched, the text does not decode, or the
   *     statement is not a parsed or failed PDF statement
   */
  public void reseed(long statementId, String rawToon) {
    statementService.get(statementId);
    if (!matchRepository.findMatches(statementId).isEmpty()) {
      throw new StatementFormatException("Unmatch every line before re-seeding.");
    }
    ParsedStatement parsed =
        decoder
            .decode(rawToon)
            .orElseThrow(
                () -> new StatementFormatException("Could not decode the text — nothing changed."));
    results.applyReseed(statementId, parsed, rawToon);
    LOG.info("Statement {} re-seeded: lines={}", statementId, parsed.lines().size());
  }

  /**
   * Parse the statement's text now and wait for the outcome: a PDF (rules) profile runs its parse
   * rules locally (statements.md §3.2a), any other PDF profile calls the AI.
   *
   * @return true when the response decoded and the statement is {@code processed}; false when it
   *     landed {@code failed} (its error is kept for the page)
   * @throws StatementFormatException when the statement is not a PDF statement awaiting a parse,
   *     or, for parse rules, when a line is matched
   */
  // AvoidCatchingGenericException: an unexpected error must still land the statement in `failed`
  // rather than leave it stuck `processing`.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public boolean parse(long statementId) {
    Statement statement = statementService.get(statementId);
    StatementProfile profile = profileService.getIncludingDeleted(statement.statementProfileId());
    if (profile.usesParseRules()) {
      return parseByRules(statement, profile);
    }
    if (!statementRepository.claimForParse(statementId)) {
      throw new StatementFormatException("This statement is not waiting to be parsed.");
    }
    try {
      return callAndStore(statement);
    } catch (StatementParseException e) {
      LOG.warn("Statement {} parse failed: {}", statementId, e.getMessage());
      results.failTransport(statementId, e.getMessage());
    } catch (RuntimeException e) {
      LOG.error("Statement {} parse errored unexpectedly", statementId, e);
      results.failTransport(statementId, "Unexpected error: " + e.getMessage());
    }
    return false;
  }

  /**
   * The rules parse: no claim, no call, no usage — the engine is local and instant, so it may run
   * again on a {@code processed} statement, replacing the TOON, header and lines. Refused while any
   * line is matched, like a re-seed.
   */
  private boolean parseByRules(Statement statement, StatementProfile profile) {
    long statementId = statement.statementId();
    if (!matchRepository.findMatches(statementId).isEmpty()) {
      throw new StatementFormatException("Unmatch every line before parsing again.");
    }
    String text = statementRepository.findSentText(statementId).orElse("");
    if (text.isBlank()) {
      results.failTransport(statementId, "The statement has no text to parse");
      return false;
    }
    String toon;
    try {
      toon =
          ruleEngine.toToon(
              text, profileService.rules(statement.statementProfileId()), profile.rulesSettings());
    } catch (StatementFormatException e) {
      LOG.warn("Statement {} rules parse failed: {}", statementId, e.getMessage());
      results.failTransport(statementId, e.getMessage());
      return false;
    }
    Optional<ParsedStatement> decoded = decoder.decode(toon);
    if (decoded.isEmpty() || decoded.get().lines().isEmpty()) {
      LOG.warn("Statement {} rules parse found no booking", statementId);
      results.failTransport(
          statementId, "No booking found — check the profile's rules against the text");
      return false;
    }
    results.applyRules(statementId, decoded.get(), toon);
    LOG.info("Statement {} parsed by rules: lines={}", statementId, decoded.get().lines().size());
    return true;
  }

  private boolean callAndStore(Statement statement) {
    long statementId = statement.statementId();
    AiSettings config = settingsService.aiConfig();
    String sentText = statementRepository.findSentText(statementId).orElse("");
    if (sentText.isBlank()) {
      throw new StatementParseException("The statement has no text to parse");
    }
    String aiNote = profileService.getIncludingDeleted(statement.statementProfileId()).aiNote();
    StatementParseResult result =
        parser.parse(
            new StatementParseRequest(
                config.model(),
                config.apiKey(),
                promptBuilder.build(settingsService.statementSystemPrompt()),
                promptBuilder.userText(aiNote, sentText)));
    BigDecimal cost =
        config.costOf(
            result.tokensIn(),
            result.tokensOut(),
            result.tokensCacheWrite(),
            result.tokensCacheRead());
    ParseUsage usage =
        new ParseUsage(
            result.tokensIn(),
            result.tokensOut(),
            result.tokensCacheWrite(),
            result.tokensCacheRead(),
            cost);
    Optional<ParsedStatement> decoded = decoder.decode(result.rawToon());
    if (decoded.isEmpty()) {
      LOG.warn("Statement {} parse response could not be decoded", statementId);
      results.failUndecodable(
          statementId, "Could not decode the parser response", usage, result.rawToon());
      return false;
    }
    results.applyProcessed(statementId, decoded.get(), usage, result.rawToon());
    LOG.info(
        "Statement {} processed: lines={} tokensIn={} tokensOut={} cost={}",
        statementId,
        decoded.get().lines().size(),
        result.tokensIn(),
        result.tokensOut(),
        cost);
    return true;
  }

  /** On boot, fail statements a restart left {@code processing}: their call died with the JVM. */
  @EventListener(ApplicationReadyEvent.class)
  public void sweepOrphansOnStartup() {
    int swept =
        statementRepository.failOrphanedProcessing("Interrupted by a restart while processing");
    if (swept > 0) {
      LOG.info("Startup sweep failed {} orphaned processing statement(s)", swept);
    }
  }
}
