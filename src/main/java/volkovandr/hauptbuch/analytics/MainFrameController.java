package volkovandr.hauptbuch.analytics;

import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The main page's own 1x1 Layout (reporting.md §11, plan stage c): one Frame, defaulting to the net
 * worth over time Preset, with a picker to show any other Preset instead. Lazy-loaded from the
 * landing page above the Balances panel, the same {@code hx-get} idiom {@link
 * TrackingStatsController} uses; the picker then re-renders the Frame in place on {@code change},
 * the same idiom the settle-up screen's account picker uses.
 */
@Controller
class MainFrameController {

  private static final String PATH = "/overview/main-frame";
  private static final String FRAME = "fragments/main-frame :: frame";

  private final LayoutService layoutService;
  private final ReportEngine reportEngine;
  private final SettingsService settingsService;

  MainFrameController(
      LayoutService layoutService, ReportEngine reportEngine, SettingsService settingsService) {
    this.layoutService = layoutService;
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
  }

  @GetMapping(PATH)
  String mainFrame(Model model) {
    return render(layoutService.mainFramePresetSlug(), model);
  }

  /** The picker's {@code change} handler: saves the choice, then re-renders the Frame with it. */
  @PostMapping(PATH)
  String updateMainFrame(@RequestParam String presetSlug, Model model) {
    layoutService.updateMainFramePreset(presetSlug);
    return render(Optional.of(presetSlug), model);
  }

  private String render(Optional<String> selectedSlug, Model model) {
    model.addAttribute("presetOptions", PresetCatalog.all());
    model.addAttribute("selectedSlug", selectedSlug.orElse(null));

    Optional<PresetDef> def = selectedSlug.flatMap(PresetCatalog::find);
    model.addAttribute("configured", def.isPresent());
    def.ifPresent(d -> populate(d, model));

    return FRAME;
  }

  private void populate(PresetDef def, Model model) {
    Optional<String> baseCurrency = settingsService.baseCurrency();
    model.addAttribute("baseCurrencyUnset", baseCurrency.isEmpty());
    baseCurrency.ifPresent(
        currency ->
            PresetRendering.populate(
                def, currency, def.renderer() == Renderer.TABLE, reportEngine, model));
  }
}
