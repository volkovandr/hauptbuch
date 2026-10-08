package volkovandr.hauptbuch.statements;

import java.util.List;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.ledger.ReconciledLegNotices;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;

/**
 * Tells the register's dock which statement proved each matched leg of the transaction it edits
 * (statements.md §6.4) — a muted notice, never a block.
 */
@Component
class StatementLegNotices implements ReconciledLegNotices {

  private final StatementMatchRepository matchRepository;

  StatementLegNotices(StatementMatchRepository matchRepository) {
    this.matchRepository = matchRepository;
  }

  @Override
  public List<String> noticesFor(long transactionId) {
    return matchRepository.findStatementsOfTransaction(transactionId).stream()
        .map(StatementOfLeg::notice)
        .toList();
  }
}
