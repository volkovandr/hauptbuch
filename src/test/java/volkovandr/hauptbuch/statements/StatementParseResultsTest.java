package volkovandr.hauptbuch.statements;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.statements.ParsedStatement.ParsedLine;
import volkovandr.hauptbuch.statements.repository.ParseUsage;
import volkovandr.hauptbuch.statements.repository.StatementLineRepository;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * Unit tier: a decoded parse replaces the old lines before the new ones arrive, and the header and
 * state land last — one transaction, so a failure mid-way leaves the old lines.
 */
@ExtendWith(MockitoExtension.class)
class StatementParseResultsTest {

  private static final long ID = 21L;
  private static final ParseUsage USAGE = new ParseUsage(1, 2, 3, 4, new BigDecimal("0.01"));

  @Mock private StatementRepository statements;
  @Mock private StatementLineRepository lines;

  @Test
  void appliesTheParseReplacingTheLinesThenTheHeader() {
    StatementLine line =
        new StatementLine(
            null,
            0,
            LocalDate.of(2026, 5, 2),
            null,
            new BigDecimal("-1"),
            null,
            null,
            null,
            null,
            null);
    ParsedStatement parsed =
        new ParsedStatement(
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31),
            BigDecimal.TEN,
            BigDecimal.ONE,
            List.of(new ParsedLine(line, BigDecimal.TWO, "USD", new BigDecimal("1.1"))));

    new StatementParseResults(statements, lines).applyProcessed(ID, parsed, USAGE, "raw");

    InOrder order = inOrder(lines, statements);
    order.verify(lines).deleteByStatement(ID);
    order.verify(lines).insertParsed(ID, line, BigDecimal.TWO, "USD", new BigDecimal("1.1"));
    order
        .verify(statements)
        .markProcessed(
            ID,
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31),
            BigDecimal.TEN,
            BigDecimal.ONE,
            USAGE,
            "raw");
  }

  @Test
  void recordsBothKindsOfFailure() {
    StatementParseResults results = new StatementParseResults(statements, lines);

    results.failUndecodable(ID, "bad", USAGE, "raw");
    results.failTransport(ID, "down");

    verify(statements).markFailedWithResult(ID, "bad", USAGE, "raw");
    verify(statements).markFailed(ID, "down");
    verify(lines, never()).deleteByStatement(any(Long.class));
  }
}
