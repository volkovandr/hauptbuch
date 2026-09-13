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
 * unknown-preset/unknown-report and out-of-bounds-Frame rejections (CLAUDE.md §1.7) are decidable
 * without the DB and belong here; the persistence round-trip is {@link
 * LayoutRepositoryIntegrationTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
class LayoutServiceTest {

  @Mock private LayoutRepository layoutRepository;
  @Mock private ReportService reportService;
  @InjectMocks private LayoutService layoutService;

  private static final SavedReport A_SAVED_REPORT =
      new SavedReport(42L, "My report", Presets.balanceSheet(), Renderer.TABLE, false);

  @Test
  void mainFrameSelectionReadsTheMainPageLayoutsOneFrame() {
    when(layoutRepository.findByPage(LayoutService.MAIN_PAGE))
        .thenReturn(
            Optional.of(
                new LayoutSnapshot(
                    1, 1, List.of(new LayoutFrameRow(0, 0, "balance-sheet", null)))));

    assertThat(layoutService.mainFrameSelection())
        .isEqualTo(new FrameSelection("balance-sheet", null));
  }

  @Test
  void mainFrameSelectionReadsSavedReport() {
    when(layoutRepository.findByPage(LayoutService.MAIN_PAGE))
        .thenReturn(
            Optional.of(new LayoutSnapshot(1, 1, List.of(new LayoutFrameRow(0, 0, null, 42L)))));

    assertThat(layoutService.mainFrameSelection()).isEqualTo(new FrameSelection(null, 42L));
  }

  @Test
  void updateMainFrameWritesKnownPresetAsOneByOneLayout() {
    layoutService.updateMainFrame(new FrameSelection("balance-sheet", null));

    verify(layoutRepository)
        .save(
            eq(LayoutService.MAIN_PAGE),
            eq(1),
            eq(1),
            eq(List.of(new LayoutFrameRow(0, 0, "balance-sheet", null))));
  }

  @Test
  void updateMainFrameWritesKnownReportAsOneByOneLayout() {
    when(reportService.find(42L)).thenReturn(Optional.of(A_SAVED_REPORT));

    layoutService.updateMainFrame(new FrameSelection(null, 42L));

    verify(layoutRepository)
        .save(
            eq(LayoutService.MAIN_PAGE),
            eq(1),
            eq(1),
            eq(List.of(new LayoutFrameRow(0, 0, null, 42L))));
  }

  @Test
  void updateMainFrameRejectsUnknownPresetBeforeWriting() {
    assertThatThrownBy(
            () -> layoutService.updateMainFrame(new FrameSelection("no-such-preset", null)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }

  @Test
  void updateMainFrameRejectsUnknownReportBeforeWriting() {
    when(reportService.find(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> layoutService.updateMainFrame(new FrameSelection(null, 404L)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }

  @Test
  void saveReportsLayoutWritesValidGrid() {
    List<LayoutFrameRow> frames =
        List.of(
            new LayoutFrameRow(0, 0, "balance-sheet", null), new LayoutFrameRow(0, 1, null, null));

    layoutService.saveReportsLayout(1, 2, frames);

    verify(layoutRepository).save(LayoutService.REPORTS_PAGE, 1, 2, frames);
  }

  @Test
  void saveReportsLayoutWritesFrameReferencingSavedReport() {
    when(reportService.find(42L)).thenReturn(Optional.of(A_SAVED_REPORT));
    List<LayoutFrameRow> frames = List.of(new LayoutFrameRow(0, 0, null, 42L));

    layoutService.saveReportsLayout(1, 1, frames);

    verify(layoutRepository).save(LayoutService.REPORTS_PAGE, 1, 1, frames);
  }

  @Test
  void saveReportsLayoutRejectsDegenerateGridBeforeWriting() {
    assertThatThrownBy(() -> layoutService.saveReportsLayout(0, 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }

  @Test
  void saveReportsLayoutRejectsFrameOutsideGridBeforeWriting() {
    List<LayoutFrameRow> frames = List.of(new LayoutFrameRow(1, 0, "balance-sheet", null));

    assertThatThrownBy(() -> layoutService.saveReportsLayout(1, 1, frames))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }

  @Test
  void saveReportsLayoutRejectsAnUnknownPresetBeforeWriting() {
    List<LayoutFrameRow> frames = List.of(new LayoutFrameRow(0, 0, "no-such-preset", null));

    assertThatThrownBy(() -> layoutService.saveReportsLayout(1, 1, frames))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }

  @Test
  void saveReportsLayoutRejectsAnUnknownReportBeforeWriting() {
    when(reportService.find(404L)).thenReturn(Optional.empty());
    List<LayoutFrameRow> frames = List.of(new LayoutFrameRow(0, 0, null, 404L));

    assertThatThrownBy(() -> layoutService.saveReportsLayout(1, 1, frames))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(layoutRepository);
  }
}
