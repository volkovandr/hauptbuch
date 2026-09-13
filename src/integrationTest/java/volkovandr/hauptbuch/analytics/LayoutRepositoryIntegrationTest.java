package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.analytics.repository.LayoutFrameRow;
import volkovandr.hauptbuch.analytics.repository.LayoutRepository;
import volkovandr.hauptbuch.analytics.repository.LayoutSnapshot;

/**
 * Integration tier (CLAUDE.md §6): the {@code layout} / {@code layout_frame} round-trip
 * (reporting.md §11, plan stage c) — V25/V26's two seeded pages, and the whole-Layout replace
 * {@code save} does. Flyway applies V25/V26; each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LayoutRepositoryIntegrationTest {

  @Autowired LayoutRepository layoutRepository;

  @Test
  void mainPageDefaultsToOneByOneNetWorthOverTime() {
    LayoutSnapshot layout = layoutRepository.findByPage("main").orElseThrow();

    assertThat(layout.rowCount()).isEqualTo(1);
    assertThat(layout.columnCount()).isEqualTo(1);
    assertThat(layout.frames()).containsExactly(new LayoutFrameRow(0, 0, "net-worth-over-time"));
  }

  @Test
  void reportsPageDefaultsToOneByOneWithAnEmptyFrame() {
    LayoutSnapshot layout = layoutRepository.findByPage("reports").orElseThrow();

    assertThat(layout.rowCount()).isEqualTo(1);
    assertThat(layout.columnCount()).isEqualTo(1);
    assertThat(layout.frames()).containsExactly(new LayoutFrameRow(0, 0, null));
  }

  @Test
  void anUnknownPageIsEmpty() {
    assertThat(layoutRepository.findByPage("no-such-page")).isEmpty();
  }

  @Test
  void saveReplacesGridDimensionsAndEveryFrame() {
    List<LayoutFrameRow> frames =
        List.of(
            new LayoutFrameRow(0, 0, "category-month-matrix"),
            new LayoutFrameRow(0, 1, null),
            new LayoutFrameRow(1, 0, "balance-sheet"),
            new LayoutFrameRow(1, 1, "this-month-vs-last"));

    layoutRepository.save("reports", 2, 2, frames);

    LayoutSnapshot layout = layoutRepository.findByPage("reports").orElseThrow();
    assertThat(layout.rowCount()).isEqualTo(2);
    assertThat(layout.columnCount()).isEqualTo(2);
    assertThat(layout.frames()).containsExactlyInAnyOrderElementsOf(frames);
  }

  @Test
  void saveShrinkingTheGridDropsTheSurplusFrames() {
    layoutRepository.save(
        "reports",
        2,
        2,
        List.of(
            new LayoutFrameRow(0, 0, "balance-sheet"),
            new LayoutFrameRow(0, 1, null),
            new LayoutFrameRow(1, 0, null),
            new LayoutFrameRow(1, 1, null)));

    layoutRepository.save("reports", 1, 1, List.of(new LayoutFrameRow(0, 0, "balance-sheet")));

    LayoutSnapshot layout = layoutRepository.findByPage("reports").orElseThrow();
    assertThat(layout.rowCount()).isEqualTo(1);
    assertThat(layout.columnCount()).isEqualTo(1);
    assertThat(layout.frames()).containsExactly(new LayoutFrameRow(0, 0, "balance-sheet"));
  }

  @Test
  void savingTheMainPageDoesNotTouchTheReportsPage() {
    layoutRepository.save("main", 1, 1, List.of(new LayoutFrameRow(0, 0, "balance-sheet")));

    LayoutSnapshot reports = layoutRepository.findByPage("reports").orElseThrow();
    assertThat(reports.frames()).containsExactly(new LayoutFrameRow(0, 0, null));
  }
}
