package volkovandr.hauptbuch.analytics;

import jakarta.servlet.http.HttpServletResponse;
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
 * stage d2): {@code /reports} shows the Layout read-only — its Frames and nothing else, no
 * rows/columns inputs, no dropdowns — then the <b>My reports</b> and <b>Presets</b> lists, then
 * <b>Edit layout</b>. {@code /reports/layout} is the separate editing page: rows x columns and a
 * picker per Frame above its live-rendered preview ({@code fragments/frame.html}, shared with the
 * read-only page so neither can drift on how a Frame looks); resizing the grid only re-renders the
 * {@code fragments/layout-config} fragment in place (unpersisted), while {@code Save layout}
 * persists the whole grid at once and navigates back to {@code /reports} via the {@code
 * HX-Redirect} response header (the receipt-confirm screen's confirm-and-advance idiom) rather than
 * a real form submit — matching {@code Cancel}'s own plain navigation back (reporting.md §11's "no
 * cap, no drag"). The saved-Report list lives here too, since it shares {@code /reports}; each
 * Report's own actions are {@link SavedReportController}'s job.
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

  /** The reporting page: the Layout read-only, the saved-Report list, the Presets list. */
  @GetMapping(BASE_PATH)
  String reports(Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Reports · Hauptbuch");
    model.addAttribute("savedReports", reportService.list());
    model.addAttribute("presets", PresetCatalog.all());

    LayoutSnapshot layout = layoutService.reportsLayout();
    model.addAttribute(
        "frames", frameViews(layout.rowCount(), layout.columnCount(), layout.frames(), false));
    model.addAttribute("columnCount", layout.columnCount());
    return "reports";
  }

  /** {@code /reports/layout}: the Layout's editor — rows x columns and a picker per Frame. */
  @GetMapping(LAYOUT_PATH)
  String editLayout(Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Edit layout · Hauptbuch");
    LayoutSnapshot layout = layoutService.reportsLayout();
    populateLayout(layout.rowCount(), layout.columnCount(), layout.frames(), model);
    return "reports-layout";
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

  /**
   * {@code Save layout}: persists the grid's current shape and every Frame's selection, then
   * navigates back to {@code /reports} via {@code HX-Redirect} — reporting.md §11 spells out "Save
   * layout or Cancel both return to /reports" as a real navigation, not an in-place swap, but a
   * plain {@code type="submit"} button would let Enter in the Rows/Columns inputs (which only
   * resize on {@code change}) submit the stale, not-yet-resized field set.
   */
  @PostMapping(LAYOUT_PATH)
  String save(
      @RequestParam int rowCount,
      @RequestParam int columnCount,
      @RequestParam Map<String, String> allParams,
      Model model,
      HttpServletResponse response) {
    List<LayoutFrameRow> frames = parseFrames(allParams, rowCount, columnCount);
    layoutService.saveReportsLayout(rowCount, columnCount, frames);
    response.setHeader("HX-Redirect", BASE_PATH);
    populateLayout(rowCount, columnCount, frames, model);
    return LAYOUT_FRAGMENT;
  }

  private void populateLayout(
      int rowCount, int columnCount, List<LayoutFrameRow> frames, Model model) {
    model.addAttribute("rowCount", rowCount);
    model.addAttribute("columnCount", columnCount);
    model.addAttribute("presetOptions", PresetCatalog.all());
    model.addAttribute("savedReportOptions", reportService.list());
    model.addAttribute("frames", frameViews(rowCount, columnCount, frames, true));
  }

  /**
   * Every grid position's {@link FrameView}, row-major — the one place {@code /reports} (no field
   * names, unindexed) and {@link #populateLayout} (a {@code frame-row-col} field name per position,
   * for the editor's pickers) build that list, so the two cannot silently diverge on how a Frame
   * resolves.
   */
  private List<FrameView> frameViews(
      int rowCount, int columnCount, List<LayoutFrameRow> frames, boolean withFieldNames) {
    Optional<String> baseCurrency = settingsService.baseCurrency();
    List<FrameView> views = new ArrayList<>();
    for (int row = 0; row < rowCount; row++) {
      for (int col = 0; col < columnCount; col++) {
        String fieldName = withFieldNames ? FRAME_PARAM_PREFIX + row + "-" + col : null;
        views.add(frameView(selectionAt(frames, row, col), baseCurrency, fieldName));
      }
    }
    return views;
  }

  private FrameView frameView(
      FrameSelection selection, Optional<String> baseCurrency, String fieldName) {
    PresetRendering.FrameContent content =
        PresetRendering.renderFrame(selection, baseCurrency, reportEngine, reportService);
    String openFullReportUrl = content.configured() ? selection.fullReportUrl() : null;
    return new FrameView(
        fieldName,
        selection.encoded(),
        content.configured(),
        content.baseCurrencyUnset(),
        content.title(),
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
