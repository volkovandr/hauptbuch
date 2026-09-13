package volkovandr.hauptbuch.analytics;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.analytics.repository.LayoutFrameRow;
import volkovandr.hauptbuch.analytics.repository.LayoutRepository;
import volkovandr.hauptbuch.analytics.repository.LayoutSnapshot;

/**
 * A page's Layout (reporting.md §11, plan stage c; a Frame's saved-Report reference is stage d's
 * follow-up): the main page's own 1x1 Layout (one Frame, changeable from its picker — {@link
 * MainFrameController}) and the reporting page's own, configurable-shape Layout ({@link
 * ReportsLayoutController}) both go through this one mechanism, so the two cannot drift on what a
 * Frame or a {@link FrameSelection} means.
 */
@Service
class LayoutService {

  static final String MAIN_PAGE = "main";
  static final String REPORTS_PAGE = "reports";

  private final LayoutRepository layoutRepository;
  private final ReportService reportService;

  LayoutService(LayoutRepository layoutRepository, ReportService reportService) {
    this.layoutRepository = layoutRepository;
    this.reportService = reportService;
  }

  /** The main page Frame's current selection — {@link FrameSelection#EMPTY} when it is cleared. */
  FrameSelection mainFrameSelection() {
    return layoutRepository
        .findByPage(MAIN_PAGE)
        .flatMap(layout -> layout.frames().stream().findFirst())
        .map(frame -> new FrameSelection(frame.presetSlug(), frame.reportId()))
        .orElse(FrameSelection.EMPTY);
  }

  /**
   * Points the main page Frame at a different Preset or saved Report. Rejected before the write if
   * {@code selection} names no known Preset/Report — the service upholds this invariant, not the
   * repository (CLAUDE.md §1.7). {@code @Transactional} because {@link LayoutRepository#save} is a
   * delete-then-reinsert, not one statement — a mid-write failure must not leave the Frame
   * half-replaced.
   */
  @Transactional
  void updateMainFrame(FrameSelection selection) {
    requireKnownOrEmpty(selection);
    layoutRepository.save(
        MAIN_PAGE,
        1,
        1,
        List.of(new LayoutFrameRow(0, 0, selection.presetSlug(), selection.reportId())));
  }

  /** The reporting page's Layout — always present (seeded by V26). */
  LayoutSnapshot reportsLayout() {
    return layoutRepository
        .findByPage(REPORTS_PAGE)
        .orElseThrow(() -> new IllegalStateException("The reporting page has no Layout row."));
  }

  /**
   * Replaces the reporting page's Layout wholesale — its grid dimensions and every Frame's
   * selection, the one {@code Save layout} action (reporting.md §11). Rejected before the write if
   * the grid is degenerate, a Frame falls outside it, or a Frame names an unknown Preset/Report.
   * {@code @Transactional} for the same reason {@link #updateMainFrame} is — {@link
   * LayoutRepository#save} touches several rows across two statements' worth of work.
   */
  @Transactional
  void saveReportsLayout(int rowCount, int columnCount, List<LayoutFrameRow> frames) {
    if (rowCount < 1 || columnCount < 1) {
      throw new IllegalArgumentException("A Layout needs at least one row and one column.");
    }
    frames.forEach(frame -> requireWithinGrid(frame, rowCount, columnCount));
    layoutRepository.save(REPORTS_PAGE, rowCount, columnCount, frames);
  }

  private void requireWithinGrid(LayoutFrameRow frame, int rowCount, int columnCount) {
    boolean withinGrid =
        frame.rowPosition() >= 0
            && frame.rowPosition() < rowCount
            && frame.colPosition() >= 0
            && frame.colPosition() < columnCount;
    if (!withinGrid) {
      throw new IllegalArgumentException("A Frame position falls outside the Layout's grid.");
    }
    requireKnownOrEmpty(new FrameSelection(frame.presetSlug(), frame.reportId()));
  }

  private void requireKnownOrEmpty(FrameSelection selection) {
    if (selection.presetSlug() != null && PresetCatalog.find(selection.presetSlug()).isEmpty()) {
      throw new IllegalArgumentException("No such preset: " + selection.presetSlug());
    }
    if (selection.reportId() != null && reportService.find(selection.reportId()).isEmpty()) {
      throw new IllegalArgumentException("No such report: " + selection.reportId());
    }
  }
}
