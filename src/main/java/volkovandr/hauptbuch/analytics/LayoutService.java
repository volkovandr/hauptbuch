package volkovandr.hauptbuch.analytics;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.analytics.repository.LayoutFrameRow;
import volkovandr.hauptbuch.analytics.repository.LayoutRepository;
import volkovandr.hauptbuch.analytics.repository.LayoutSnapshot;

/**
 * A page's Layout (reporting.md §11, plan stage c): the main page's own 1x1 Layout (one Frame,
 * changeable from its picker — {@link MainFrameController}) and the reporting page's own,
 * configurable-shape Layout ({@link ReportsLayoutController}) both go through this one mechanism,
 * so the two cannot drift on what a Frame or a Preset choice means.
 */
@Service
class LayoutService {

  static final String MAIN_PAGE = "main";
  static final String REPORTS_PAGE = "reports";

  private final LayoutRepository layoutRepository;

  LayoutService(LayoutRepository layoutRepository) {
    this.layoutRepository = layoutRepository;
  }

  /** The main page Frame's configured Preset slug — empty when the Frame has been cleared. */
  Optional<String> mainFramePresetSlug() {
    return layoutRepository
        .findByPage(MAIN_PAGE)
        .flatMap(layout -> layout.frames().stream().findFirst())
        .map(LayoutFrameRow::presetSlug);
  }

  /**
   * Points the main page Frame at a different Preset. Rejected before the write if the slug names
   * no known Preset — the service upholds this invariant, not the repository (CLAUDE.md §1.7).
   * {@code @Transactional} because {@link LayoutRepository#save} is a delete-then-reinsert, not one
   * statement — a mid-write failure must not leave the Frame half-replaced.
   */
  @Transactional
  void updateMainFramePreset(String presetSlug) {
    requireKnownOrEmpty(presetSlug);
    layoutRepository.save(MAIN_PAGE, 1, 1, List.of(new LayoutFrameRow(0, 0, presetSlug)));
  }

  /** The reporting page's Layout — always present (seeded by V26). */
  LayoutSnapshot reportsLayout() {
    return layoutRepository
        .findByPage(REPORTS_PAGE)
        .orElseThrow(() -> new IllegalStateException("The reporting page has no Layout row."));
  }

  /**
   * Replaces the reporting page's Layout wholesale — its grid dimensions and every Frame's Preset,
   * the one {@code Save layout} action (reporting.md §11). Rejected before the write if the grid is
   * degenerate, a Frame falls outside it, or a Frame names an unknown Preset.
   * {@code @Transactional} for the same reason {@link #updateMainFramePreset} is — {@link
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

  private static void requireWithinGrid(LayoutFrameRow frame, int rowCount, int columnCount) {
    boolean withinGrid =
        frame.rowPosition() >= 0
            && frame.rowPosition() < rowCount
            && frame.colPosition() >= 0
            && frame.colPosition() < columnCount;
    if (!withinGrid) {
      throw new IllegalArgumentException("A Frame position falls outside the Layout's grid.");
    }
    requireKnownOrEmpty(frame.presetSlug());
  }

  private static void requireKnownOrEmpty(String presetSlug) {
    if (presetSlug != null && PresetCatalog.find(presetSlug).isEmpty()) {
      throw new IllegalArgumentException("No such preset: " + presetSlug);
    }
  }
}
