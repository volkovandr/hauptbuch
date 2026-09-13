package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.analytics.repository.LayoutFrameRow;
import volkovandr.hauptbuch.analytics.repository.LayoutRepository;
import volkovandr.hauptbuch.analytics.repository.LayoutSnapshot;

/**
 * Unit tier: {@link LayoutService} orchestration with {@link LayoutRepository} mocked — the
 * unknown-slug and out-of-bounds-Frame rejections (CLAUDE.md §1.7) are decidable without the DB and
 * belong here; the persistence round-trip is {@link LayoutRepositoryIntegrationTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
class LayoutServiceTest {

  @Mock private LayoutRepository layoutRepository;
  @InjectMocks private LayoutService layoutService;

  @Test
  void mainFramePresetSlugReadsTheMainPageLayoutsOneFrame() {
    when(layoutRepository.findByPage(LayoutService.MAIN_PAGE))
        .thenReturn(
            Optional.of(
                new LayoutSnapshot(1, 1, List.of(new LayoutFrameRow(0, 0, "balance-sheet")))));

    assertThat(layoutService.mainFramePresetSlug()).contains("balance-sheet");
  }

  @Test
  void updateMainFramePresetWritesKnownSlugAsOneByOneLayout() {
    layoutService.updateMainFramePreset("balance-sheet");

    verify(layoutRepository)
        .save(
            eq(LayoutService.MAIN_PAGE),
            eq(1),
            eq(1),
            eq(List.of(new LayoutFrameRow(0, 0, "balance-sheet"))));
  }

  @Test
  void updateMainFramePresetRejectsUnknownSlugBeforeWriting() {
    assertThatThrownBy(() -> layoutService.updateMainFramePreset("no-such-preset"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }

  @Test
  void saveReportsLayoutWritesValidGrid() {
    List<LayoutFrameRow> frames =
        List.of(new LayoutFrameRow(0, 0, "balance-sheet"), new LayoutFrameRow(0, 1, null));

    layoutService.saveReportsLayout(1, 2, frames);

    verify(layoutRepository).save(LayoutService.REPORTS_PAGE, 1, 2, frames);
  }

  @Test
  void saveReportsLayoutRejectsDegenerateGridBeforeWriting() {
    assertThatThrownBy(() -> layoutService.saveReportsLayout(0, 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }

  @Test
  void saveReportsLayoutRejectsFrameOutsideGridBeforeWriting() {
    List<LayoutFrameRow> frames = List.of(new LayoutFrameRow(1, 0, "balance-sheet"));

    assertThatThrownBy(() -> layoutService.saveReportsLayout(1, 1, frames))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }

  @Test
  void saveReportsLayoutRejectsAnUnknownPresetBeforeWriting() {
    List<LayoutFrameRow> frames = List.of(new LayoutFrameRow(0, 0, "no-such-preset"));

    assertThatThrownBy(() -> layoutService.saveReportsLayout(1, 1, frames))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }
}
