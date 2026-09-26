package volkovandr.hauptbuch.analytics;

import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import volkovandr.hauptbuch.ledger.RegisterService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.web.NavItem;

/**
 * {@code /reports/cell} (reporting.md §12): one figure's drill-down — the postings behind it as
 * register rows, with a running column that ends on the figure. A Report's table submits here with
 * its spec and expansion as hidden fields and the figure's {@link CellAddress#token} as {@code
 * cell}, so the list re-renders exactly the Report the figure was read from, whether it is saved, a
 * Preset or an unsaved draft. Each row's pencil hands off to the register ({@code selected=}).
 */
@Controller
class ReportDrillDownController {

  private static final String CELL_PATH = "/reports/cell";
  private static final String REPORTS_PATH = "/reports";

  private final ReportDrillDown drillDown;
  private final RegisterService registerService;
  private final SettingsService settingsService;

  ReportDrillDownController(
      ReportDrillDown drillDown, RegisterService registerService, SettingsService settingsService) {
    this.drillDown = drillDown;
    this.registerService = registerService;
    this.settingsService = settingsService;
  }

  @GetMapping(CELL_PATH)
  String drill(
      @RequestParam MultiValueMap<String, String> params,
      @RequestParam String cell,
      @RequestHeader(value = "Referer", required = false) String referer,
      Model model) {
    model.addAttribute("nav", NavItem.sectionsFor(REPORTS_PATH));
    model.addAttribute("title", "Drill-down · Hauptbuch");
    return settingsService
        .baseCurrency()
        .map(
            baseCurrency -> {
              DrillDown drill = drill(params, cell);
              model.addAttribute(
                  "drill",
                  DrillDownViewAssembler.assemble(
                      drill,
                      registerService.rowsForPostings(drill.postingIds()),
                      baseCurrency,
                      backUrl(referer)));
              return "report-cell";
            })
        .orElse("report-unavailable");
  }

  private DrillDown drill(MultiValueMap<String, String> params, String cell) {
    if (!ReportSpecQueryString.isPresent(params)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No Report to drill into");
    }
    ReportSpec spec;
    CellAddress address;
    try {
      spec = ReportSpecQueryString.fromParams(params);
      address = CellAddress.parse(cell);
    } catch (IllegalArgumentException | IllegalStateException malformed) {
      // A hand-edited or stale link: a missing or unknown spec value, or a cell that is not one.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a Report figure", malformed);
    }
    if (address.measureIndex() >= spec.measures().size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such measure");
    }
    Set<String> expandedKeys = RowToggle.expandedKeysFrom(params).orElse(null);
    return drillDown.drill(spec, expandedKeys, address);
  }

  /**
   * The page the figure was opened from, kept to its own path so the link never leaves the app; the
   * reporting page when the browser sent no usable {@code Referer}.
   */
  private static String backUrl(String referer) {
    if (referer == null) {
      return REPORTS_PATH;
    }
    try {
      URI uri = URI.create(referer);
      String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
      return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    } catch (IllegalArgumentException malformed) {
      return REPORTS_PATH;
    }
  }
}
