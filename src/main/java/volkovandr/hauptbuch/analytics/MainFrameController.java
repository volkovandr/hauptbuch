package volkovandr.hauptbuch.analytics;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The main page's own 1x1 Layout (reporting.md §11, plan stage d2): one Frame, defaulting to the
 * net worth over time Preset, read-only and hidden entirely while unconfigured — its picker moved
 * out to {@link #picker}, below the Balances panel, so the Frame itself reads as a clean report
 * rather than a captioned control. Both are lazy-loaded from the landing page, the same {@code
 * hx-get} idiom {@link TrackingStatsController} uses; the picker's {@code change} handler then
 * retargets the Frame elsewhere on the page (the settle-up screen's account picker idiom,
 * generalised to a non-adjacent target). {@code landing.html} itself — not either lazy-loaded
 * fragment — owns the stable {@code id="main-frame"} swap target, so the picker's retarget can
 * never race the Frame's own slower initial load (both fire on {@code hx-trigger="load"}, but only
 * the Frame's actually renders a report).
 */
@Controller
class MainFrameController {

  private static final String PATH = "/overview/main-frame";
  private static final String PICKER_PATH = PATH + "/picker";
  private static final String FRAME = "fragments/main-frame :: frame";
  private static final String PICKER_FRAGMENT = "fragments/main-frame-picker :: picker";

  private final LayoutService layoutService;
  private final ReportEngine reportEngine;
  private final SettingsService settingsService;
  private final ReportService reportService;

  MainFrameController(
      LayoutService layoutService,
      ReportEngine reportEngine,
      SettingsService settingsService,
      ReportService reportService) {
    this.layoutService = layoutService;
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
    this.reportService = reportService;
  }

  @GetMapping(PATH)
  String mainFrame(Model model) {
    return renderFrame(layoutService.mainFrameSelection(), model);
  }

  /** The picker, lazy-loaded separately since it no longer sits next to the Frame it controls. */
  @GetMapping(PICKER_PATH)
  String picker(Model model) {
    return renderPicker(layoutService.mainFrameSelection(), model);
  }

  /** The picker's {@code change} handler: saves the choice, then re-renders the Frame with it. */
  @PostMapping(PATH)
  String updateMainFrame(@RequestParam String selection, Model model) {
    FrameSelection decoded = FrameSelection.decode(selection);
    layoutService.updateMainFrame(decoded);
    return renderFrame(decoded, model);
  }

  private String renderFrame(FrameSelection selection, Model model) {
    PresetRendering.FrameContent content =
        PresetRendering.renderFrame(
            selection, settingsService.baseCurrency(), reportEngine, reportService);
    String openFullReportUrl = content.configured() ? selection.fullReportUrl() : null;
    model.addAttribute(
        "frame",
        new FrameView(
            null,
            selection.encoded(),
            content.configured(),
            content.baseCurrencyUnset(),
            content.title(),
            content.report(),
            content.chart(),
            openFullReportUrl));
    return FRAME;
  }

  private String renderPicker(FrameSelection selection, Model model) {
    model.addAttribute("presetOptions", PresetCatalog.all());
    model.addAttribute("savedReportOptions", reportService.list());
    model.addAttribute("selectedValue", selection.encoded());
    model.addAttribute("configured", PresetRendering.isKnown(selection, reportService));
    return PICKER_FRAGMENT;
  }
}
