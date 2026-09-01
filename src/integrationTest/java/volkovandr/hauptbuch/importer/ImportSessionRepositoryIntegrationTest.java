package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.importer.repository.ImportSessionRepository;

/**
 * Integration tier (CLAUDE.md §6): row-mapping round-trips for {@link ImportSessionRepository}
 * against real Postgres. Flyway applies V19; each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportSessionRepositoryIntegrationTest {

  @Autowired ImportSessionRepository importSessionRepository;

  @Test
  void insertOpenAndFindRoundTrip() {
    ImportSession opened = importSessionRepository.insertOpen();

    assertThat(opened.importSessionId()).isNotNull();
    assertThat(opened.state()).isEqualTo(ImportSessionState.OPEN);
    assertThat(opened.defaultCharset()).isNull();
    assertThat(opened.defaultDateOrder()).isNull();
    assertThat(opened.startedAt()).isNotNull();
    assertThat(opened.committedAt()).isNull();

    assertThat(importSessionRepository.findOpen()).contains(opened);
    assertThat(importSessionRepository.findById(opened.importSessionId())).contains(opened);
  }

  @Test
  void findOpenIsEmptyWhenNoSessionIsOpen() {
    assertThat(importSessionRepository.findOpen()).isEmpty();
  }

  @Test
  void discardOpenFlipsStateAndClearsTheOpenSlot() {
    ImportSession opened = importSessionRepository.insertOpen();

    int discarded = importSessionRepository.discardOpen();

    assertThat(discarded).isEqualTo(1);
    assertThat(importSessionRepository.findOpen()).isEmpty();
    assertThat(importSessionRepository.findById(opened.importSessionId()))
        .get()
        .satisfies(session -> assertThat(session.state()).isEqualTo(ImportSessionState.DISCARDED));
  }

  @Test
  void databaseRefusesTwoOpenSessions() {
    importSessionRepository.insertOpen();

    assertThatThrownBy(importSessionRepository::insertOpen)
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
