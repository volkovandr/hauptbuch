package volkovandr.hauptbuch.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.analytics.repository.LayoutFrameRow;
import volkovandr.hauptbuch.analytics.repository.LayoutSnapshot;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The reporting page ({@code /reports}) and its own configurable Layout (reporting.md §11, plan
 * stage c part 2; a Frame's saved-Report reference is stage d's follow-up): a rows x columns grid
 * of Frames, each a picker plus its live rendered Preset or saved Report — the same mechanism
 * {@link MainFrameController} uses for the main page's 1x1 case, generalised to N Frames. Resizing
 * the grid only re-renders the {@code fragments/layout-config} fragment in place (nothing is
 * persisted); {@code Save layout} posts the same form's current field values and persists the whole
 * grid at once (reporting.md §11's "no cap, no drag"). The saved-Report list (plan stage d) lives
 * here too, since it shares this page; each Report's own actions are {@link
 * SavedReportController}'s job.
 */
@Controller
class ReportsLayoutController {

  private static final String BASE_PATH = "/reports";
  private static final String LAYOUT_PATH = BASE_PATH + "/layout";
  private static final String RESIZE_PATH = LAYOUT_PATH + "/resize";
  private static final String LAYOUT_FRAGMENT = "fragments/layout-config :: config";
  private static final String FRAME_PARAM_PREFIX = "frame-";

  private final LayoutService layoutService;
  private final ReportEngine reportEngine;
  private final SettingsService settingsService;
  private final ReportService reportService;

  ReportsLayoutController(
      LayoutService layoutService,
      ReportEngine reportEngine,
      SettingsService settingsService,
      ReportService reportService) {
    this.layoutService = layoutService;
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
    this.reportService = reportService;
  }

  /** The reporting page: the saved-Report list (plan stage d), the Layout. */
  @GetMapping(BASE_PATH)
  String reports(Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Reports · Hauptbuch");
    model.addAttribute("savedReports", reportService.list());
    LayoutSnapshot layout = layoutService.reportsLayout();
    populateLayout(layout.rowCount(), layout.columnCount(), layout.frames(), model);
    return "reports";
  }

  /**
   * A rows/columns change: re-renders the grid, carrying over every surviving Frame's picker.
   * Clamped rather than rejected — this is a live, unsaved preview (a briefly-empty number input
   * while typing is normal), unlike {@link #save} which enforces the same floor before persisting.
   */
  @PostMapping(RESIZE_PATH)
  String resize(
      @RequestParam int rowCount,
      @RequestParam int columnCount,
      @RequestParam Map<String, String> allParams,
      Model model) {
    int clampedRows = Math.max(1, rowCount);
    int clampedColumns = Math.max(1, columnCount);
    populateLayout(
        clampedRows, clampedColumns, parseFrames(allParams, clampedRows, clampedColumns), model);
    return LAYOUT_FRAGMENT;
  }

  /** {@code Save layout}: persists the grid's current shape and every Frame's selection at once. */
  @PostMapping(LAYOUT_PATH)
  String save(
      @RequestParam int rowCount,
      @RequestParam int columnCount,
      @RequestParam Map<String, String> allParams,
      Model model) {
    List<LayoutFrameRow> frames = parseFrames(allParams, rowCount, columnCount);
    layoutService.saveReportsLayout(rowCount, columnCount, frames);
    populateLayout(rowCount, columnCount, frames, model);
    return LAYOUT_FRAGMENT;
  }

  private void populateLayout(
      int rowCount, int columnCount, List<LayoutFrameRow> frames, Model model) {
    model.addAttribute("rowCount", rowCount);
    model.addAttribute("columnCount", columnCount);
    model.addAttribute("presetOptions", PresetCatalog.all());
    model.addAttribute("savedReportOptions", reportService.list());

    Optional<String> baseCurrency = settingsService.baseCurrency();
    List<FrameView> views = new ArrayList<>();
    for (int row = 0; row < rowCount; row++) {
      for (int col = 0; col < columnCount; col++) {
        views.add(frameView(row, col, selectionAt(frames, row, col), baseCurrency));
      }
    }
    model.addAttribute("frames", views);
  }

  private FrameView frameView(
      int row, int col, FrameSelection selection, Optional<String> baseCurrency) {
    String fieldName = FRAME_PARAM_PREFIX + row + "-" + col;
    PresetRendering.FrameContent content =
        PresetRendering.renderFrame(selection, baseCurrency, reportEngine, reportService);
    String openFullReportUrl = content.configured() ? selection.fullReportUrl() : null;
    return new FrameView(
        fieldName,
        selection.encoded(),
        content.configured(),
        content.baseCurrencyUnset(),
        content.report(),
        content.chart(),
        openFullReportUrl);
  }

  private static FrameSelection selectionAt(List<LayoutFrameRow> frames, int row, int col) {
    // findFirst() throws NPE on a null stream element (it wraps via Optional.of, not ofNullable) —
    // find the LayoutFrameRow itself (never null) first, then extract its (possibly null) fields.
    return frames.stream()
        .filter(f -> f.rowPosition() == row && f.colPosition() == col)
        .findFirst()
        .map(f -> new FrameSelection(f.presetSlug(), f.reportId()))
        .orElse(FrameSelection.EMPTY);
  }

  private static List<LayoutFrameRow> parseFrames(
      Map<String, String> allParams, int rowCount, int columnCount) {
    List<LayoutFrameRow> frames = new ArrayList<>();
    for (int row = 0; row < rowCount; row++) {
      for (int col = 0; col < columnCount; col++) {
        FrameSelection selection =
            FrameSelection.decode(allParams.get(FRAME_PARAM_PREFIX + row + "-" + col));
        frames.add(new LayoutFrameRow(row, col, selection.presetSlug(), selection.reportId()));
      }
    }
    return frames;
  }
}
