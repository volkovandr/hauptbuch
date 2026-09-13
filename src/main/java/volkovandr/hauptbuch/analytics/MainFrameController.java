package volkovandr.hauptbuch.analytics;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The main page's own 1x1 Layout (reporting.md §11, plan stage c; a Frame's saved-Report reference
 * is stage d's follow-up): one Frame, defaulting to the net worth over time Preset, with a picker
 * to show any other Preset or saved Report instead. Lazy-loaded from the landing page above the
 * Balances panel, the same {@code hx-get} idiom {@link TrackingStatsController} uses; the picker
 * then re-renders the Frame in place on {@code change}, the same idiom the settle-up screen's
 * account picker uses.
 */
@Controller
class MainFrameController {

  private static final String PATH = "/overview/main-frame";
  private static final String FRAME = "fragments/main-frame :: frame";

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
    return render(layoutService.mainFrameSelection(), model);
  }

  /** The picker's {@code change} handler: saves the choice, then re-renders the Frame with it. */
  @PostMapping(PATH)
  String updateMainFrame(@RequestParam String selection, Model model) {
    FrameSelection decoded = FrameSelection.decode(selection);
    layoutService.updateMainFrame(decoded);
    return render(decoded, model);
  }

  private String render(FrameSelection selection, Model model) {
    model.addAttribute("presetOptions", PresetCatalog.all());
    model.addAttribute("savedReportOptions", reportService.list());
    model.addAttribute("selectedValue", selection.encoded());

    PresetRendering.FrameContent content =
        PresetRendering.renderFrame(
            selection, settingsService.baseCurrency(), reportEngine, reportService);
    model.addAttribute("configured", content.configured());
    model.addAttribute("baseCurrencyUnset", content.baseCurrencyUnset());
    model.addAttribute("report", content.report());
    model.addAttribute("chart", content.chart());
    model.addAttribute(
        "openFullReportUrl", content.configured() ? selection.fullReportUrl() : null);

    return FRAME;
  }
}
