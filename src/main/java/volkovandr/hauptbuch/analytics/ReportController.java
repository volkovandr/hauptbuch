package volkovandr.hauptbuch.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * The reporting screens (reporting.md §14, plan stage a): the reporting-page shell (the Layout and
 * the saved-Report list ship in stage c/d — for now this lists the Presets) and the two Presets'
 * tables ({@code /reports/preset/{slug}}, code-defined and non-deletable).
 */
@Controller
class ReportController {

  private static final String BASE_PATH = "/reports";
  private static final String NO_BASE_CURRENCY_VIEW = "report-unavailable";

  private final ReportEngine reportEngine;
  private final SettingsService settingsService;

  ReportController(ReportEngine reportEngine, SettingsService settingsService) {
    this.reportEngine = reportEngine;
    this.settingsService = settingsService;
  }

  /** The reporting page: for stage a, a list of the Presets (the Layout ships in stage c). */
  @GetMapping(BASE_PATH)
  String reports(Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", "Reports · Hauptbuch");
    return "reports";
  }

  /** One Preset's table, by its URL slug (§14 — {@code /reports/preset/{slug}} is code-defined). */
  @GetMapping(BASE_PATH + "/preset/{slug}")
  String preset(@PathVariable String slug, Model model) {
    ReportSpec spec;
    String title;
    if (Presets.CATEGORY_MONTH_MATRIX_SLUG.equals(slug)) {
      spec = Presets.categoryMonthMatrix();
      title = "Category × month matrix";
    } else if (Presets.BALANCE_SHEET_SLUG.equals(slug)) {
      spec = Presets.balanceSheet();
      title = "Balance sheet";
    } else {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such preset: " + slug);
    }

    model.addAttribute("nav", NavItem.sectionsFor(BASE_PATH));
    model.addAttribute("title", title + " · Hauptbuch");

    return settingsService
        .baseCurrency()
        .map(
            baseCurrency -> {
              ReportGrid grid = reportEngine.render(spec);
              model.addAttribute(
                  "report", ReportTableViewAssembler.assemble(title, spec, grid, baseCurrency));
              return "report-table";
            })
        .orElse(NO_BASE_CURRENCY_VIEW);
  }
}
