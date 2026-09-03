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
 * The matching logic itself is SQL-resident and lives in {@link ImportMirrorMatchingSqlLogicTest}.
 */
@ExtendWith(MockitoExtension.class)
class ImportMirrorMatchingServiceTest {

  @Mock ImportSessionService importSessionService;
  @Mock ImportMirrorRepository importMirrorRepository;

  private ImportMirrorMatchingService service() {
    return new ImportMirrorMatchingService(importSessionService, importMirrorRepository);
  }

  @Test
  void reMatchesTheOpenSession() {
    when(importSessionService.currentSession())
        .thenReturn(
            Optional.of(
                new ImportSession(
                    7L, ImportSessionState.OPEN, null, null, OffsetDateTime.now(), null)));

    service().rematchCurrentSession();

    verify(importMirrorRepository).rematch(7L);
  }

  @Test
  void doesNothingWithoutAnOpenSession() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    service().rematchCurrentSession();

    verifyNoInteractions(importMirrorRepository);
  }
}
