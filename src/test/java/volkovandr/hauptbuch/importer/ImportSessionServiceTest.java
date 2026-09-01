package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.importer.repository.ImportSessionRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportSessionService} lifecycle guards with the repository
 * mocked — the "one open session at a time" rule (import.md §2) and discard's no-op.
 */
@ExtendWith(MockitoExtension.class)
class ImportSessionServiceTest {

  @Mock ImportSessionRepository importSessionRepository;
  @InjectMocks ImportSessionService service;

  private static ImportSession openSession(long id) {
    return new ImportSession(id, ImportSessionState.OPEN, null, null, OffsetDateTime.now(), null);
  }

  @Test
  void opensSessionWhenNoneInProgress() {
    when(importSessionRepository.findOpen()).thenReturn(Optional.empty());
    ImportSession opened = openSession(1L);
    when(importSessionRepository.insertOpen()).thenReturn(opened);

    assertThat(service.openSession()).isEqualTo(opened);
  }

  @Test
  void refusesSecondOpenSession() {
    when(importSessionRepository.findOpen()).thenReturn(Optional.of(openSession(3L)));

    assertThatThrownBy(service::openSession)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already open");
    verify(importSessionRepository, never()).insertOpen();
  }

  @Test
  void discardIsNoOpWhenNoSessionOpen() {
    when(importSessionRepository.discardOpen()).thenReturn(0);

    service.discardSession();

    verify(importSessionRepository).discardOpen();
  }

  @Test
  void currentSessionReflectsTheOpenOne() {
    ImportSession open = openSession(5L);
    when(importSessionRepository.findOpen()).thenReturn(Optional.of(open));

    assertThat(service.currentSession()).contains(open);
  }
}
