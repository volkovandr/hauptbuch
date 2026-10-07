package volkovandr.hauptbuch.operations;

import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.operations.repository.GhostSuggestionRepository;

/**
 * What the entry dock can pre-fill from a payee's history, for callers outside {@code operations}
 * (the statement page, statements.md §6.4). The register's own dock keeps using {@link
 * GhostSuggestionRepository} directly.
 */
@Service
public class DockPrefillService {

  private final GhostSuggestionRepository ghostSuggestionRepository;

  DockPrefillService(GhostSuggestionRepository ghostSuggestionRepository) {
    this.ghostSuggestionRepository = ghostSuggestionRepository;
  }

  /** The category of the payee's most recent live transaction, or empty when it has none. */
  public Optional<GhostSuggestion> lastCategoryOf(long payeeId) {
    return ghostSuggestionRepository.lastFor(payeeId);
  }
}
