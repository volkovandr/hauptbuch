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

  StatementParseService(
      StatementRepository statementRepository,
      StatementProfileService profileService,
      StatementService statementService,
      StatementParser parser,
      StatementPromptBuilder promptBuilder,
      ToonStatementDecoder decoder,
      StatementParseResults results,
      SettingsService settingsService,
      StatementMatchRepository matchRepository) {
    this.statementRepository = statementRepository;
    this.profileService = profileService;
    this.statementService = statementService;
    this.parser = parser;
    this.promptBuilder = promptBuilder;
    this.decoder = decoder;
    this.results = results;
    this.settingsService = settingsService;
    this.matchRepository = matchRepository;
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
   * Parse the statement's text now and wait for the outcome.
   *
   * @return true when the response decoded and the statement is {@code processed}; false when it
   *     landed {@code failed} (its error is kept for the page)
   * @throws StatementFormatException when the statement is not a PDF statement awaiting a parse
   */
  // AvoidCatchingGenericException: an unexpected error must still land the statement in `failed`
  // rather than leave it stuck `processing`.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public boolean parse(long statementId) {
    Statement statement = statementService.get(statementId);
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
