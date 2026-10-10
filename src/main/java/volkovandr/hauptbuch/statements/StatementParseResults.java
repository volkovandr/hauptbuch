package volkovandr.hauptbuch.statements;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.statements.ParsedStatement.ParsedLine;
import volkovandr.hauptbuch.statements.repository.ParseUsage;
import volkovandr.hauptbuch.statements.repository.StatementLineRepository;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * The database writes a parse ends in, each one transaction: the network call itself never runs
 * inside one ({@link StatementParseService}), so a slow model never holds a connection.
 */
@Service
class StatementParseResults {

  private final StatementRepository statementRepository;
  private final StatementLineRepository lineRepository;

  StatementParseResults(
      StatementRepository statementRepository, StatementLineRepository lineRepository) {
    this.statementRepository = statementRepository;
    this.lineRepository = lineRepository;
  }

  /** Replace the statement's lines and header with the decoded parse and mark it processed. */
  @Transactional
  void applyProcessed(long statementId, ParsedStatement parsed, ParseUsage usage, String rawToon) {
    lineRepository.deleteByStatement(statementId);
    for (ParsedLine parsedLine : parsed.lines()) {
      lineRepository.insertParsed(
          statementId,
          parsedLine.line(),
          parsedLine.originalAmount(),
          parsedLine.originalCurrency(),
          parsedLine.originalRate());
    }
    statementRepository.markProcessed(
        statementId,
        parsed.periodStart(),
        parsed.periodEnd(),
        parsed.openingBalance(),
        parsed.closingBalance(),
        usage,
        rawToon);
  }

  /**
   * Replace the lines and header with a decode of the operator's edited response — no API call, so
   * the usage stays. All or nothing: a statement that cannot be re-seeded rolls the lines back.
   */
  @Transactional
  void applyReseed(long statementId, ParsedStatement parsed, String rawToon) {
    lineRepository.deleteByStatement(statementId);
    for (ParsedLine parsedLine : parsed.lines()) {
      lineRepository.insertParsed(
          statementId,
          parsedLine.line(),
          parsedLine.originalAmount(),
          parsedLine.originalCurrency(),
          parsedLine.originalRate());
    }
    int updated =
        statementRepository.markReseeded(
            statementId,
            parsed.periodStart(),
            parsed.periodEnd(),
            parsed.openingBalance(),
            parsed.closingBalance(),
            rawToon);
    if (updated == 0) {
      throw new StatementFormatException("This statement cannot be re-seeded.");
    }
  }

  /** The call completed but its body would not decode: failed, raw body and billed usage kept. */
  @Transactional
  void failUndecodable(long statementId, String reason, ParseUsage usage, String rawToon) {
    statementRepository.markFailedWithResult(statementId, reason, usage, rawToon);
  }

  /** The call could not complete: failed with the reason, no usage. */
  @Transactional
  void failTransport(long statementId, String reason) {
    statementRepository.markFailed(statementId, reason);
  }
}
