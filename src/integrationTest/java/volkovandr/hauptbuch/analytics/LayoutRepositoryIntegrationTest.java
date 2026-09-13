package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.analytics.repository.LayoutRepository;

/**
 * Integration tier (CLAUDE.md §6): the {@code layout} / {@code layout_frame} round-trip
 * (reporting.md §11, plan stage c) — V25's seed row and the plain update by position. Flyway
 * applies V25; each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LayoutRepositoryIntegrationTest {

  @Autowired LayoutRepository layoutRepository;

  @Test
  void mainFrameDefaultsToNetWorthOverTime() {
    assertThat(layoutRepository.findMainFramePresetSlug()).contains("net-worth-over-time");
  }

  @Test
  void updateMainFramePresetRoundTrips() {
    layoutRepository.updateMainFramePreset("balance-sheet");

    assertThat(layoutRepository.findMainFramePresetSlug()).contains("balance-sheet");
  }
}
