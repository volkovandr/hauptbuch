package volkovandr.hauptbuch.statements;

import java.util.Collection;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.ledger.ReconciliationDropListener;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;

/**
 * A match exists only on a {@code reconciled} posting (statements.md §5): when {@code ledger}
 * reports legs that stopped being {@code reconciled} — an amount edit, a void — their matches go.
 */
@Component
class StatementReconciliationListener implements ReconciliationDropListener {

  private final StatementMatchRepository matchRepository;

  StatementReconciliationListener(StatementMatchRepository matchRepository) {
    this.matchRepository = matchRepository;
  }

  @Override
  public void reconciliationDropped(Collection<Long> postingIds) {
    matchRepository.deleteMatchesOnPostings(postingIds);
  }
}
