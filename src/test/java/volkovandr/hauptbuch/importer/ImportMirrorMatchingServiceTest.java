package volkovandr.hauptbuch.importer;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.importer.repository.ImportMirrorRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportMirrorMatchingService} orchestration with the repository
 * mocked — it resolves the open campaign and delegates, and is a no-op when none is open (plan e1).
 * Also triggers {@link ImportCrossCurrencyRateWriteBackService} after a rematch (plan e3); that
 * service's own candidate-forwarding logic is covered in {@link
 * ImportCrossCurrencyRateWriteBackServiceTest}. The matching logic itself is SQL-resident and lives
 * in {@link ImportMirrorMatchingSqlLogicTest}.
 */
@ExtendWith(MockitoExtension.class)
class ImportMirrorMatchingServiceTest {

  private static final long SESSION_ID = 7L;

  @Mock ImportSessionService importSessionService;
  @Mock ImportMirrorRepository importMirrorRepository;
  @Mock ImportCrossCurrencyRateWriteBackService rateWriteBackService;

  private ImportMirrorMatchingService service() {
    return new ImportMirrorMatchingService(
        importSessionService, importMirrorRepository, rateWriteBackService);
  }

  private void openSession() {
    when(importSessionService.currentSession())
        .thenReturn(
            Optional.of(
                new ImportSession(
                    SESSION_ID, ImportSessionState.OPEN, null, null, OffsetDateTime.now(), null)));
  }

  @Test
  void reMatchesTheOpenSession() {
    openSession();

    service().rematchCurrentSession();

    verify(importMirrorRepository).rematch(SESSION_ID);
  }

  @Test
  void triggersTheRateWriteBackAfterRematch() {
    openSession();

    service().rematchCurrentSession();

    verify(rateWriteBackService).writeBackObservedRates(SESSION_ID);
  }

  @Test
  void doesNothingWithoutAnOpenSession() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    service().rematchCurrentSession();

    verifyNoInteractions(importMirrorRepository);
    verifyNoInteractions(rateWriteBackService);
  }
}
