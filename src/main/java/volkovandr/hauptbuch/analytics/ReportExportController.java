package volkovandr.hauptbuch.analytics;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * {@code /reports/export} (reporting.md §13): a Report as a CSV download, as shown — the grid on
 * screen, with its expansion and totals — or raw, every hierarchy at its leaves ({@link
 * ReportEngine#renderRaw}). The editor's actions strip submits the page's effective spec and
 * expansion here, so the file matches the page whether it is a saved Report, a Preset or a draft.
 */
@Controller
class ReportExportController {

  private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

  /**
   * A spreadsheet reads UTF-8 (the {@code —} of an illegal figure) only after a byte-order mark.
   */
  private static final String BYTE_ORDER_MARK = "﻿";

  private final ReportEngine engine;
  private final SettingsService settingsService;

  ReportExportController(ReportEngine engine, SettingsService settingsService) {
    this.engine = engine;
    this.settingsService = settingsService;
  }

  @GetMapping("/reports/export")
  ResponseEntity<String> export(
      @RequestParam MultiValueMap<String, String> params,
      @RequestParam String form,
      @RequestParam(required = false) String name) {
    boolean raw = parseForm(form);
    if (settingsService.baseCurrency().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Set the base currency first");
    }
    ReportSpec spec = spec(params);
    LocalDate today = LocalDate.now();
    ReportGrid grid =
        raw
            ? engine.renderRaw(spec, today)
            : engine.render(spec, today, RowToggle.expandedKeysFrom(params).orElse(null));
    return ResponseEntity.ok()
        .contentType(CSV)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(fileName(name, raw), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(BYTE_ORDER_MARK + ReportCsv.write(spec, grid));
  }

  private static boolean parseForm(String form) {
    return switch (form) {
      case "shown" -> false;
      case "raw" -> true;
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such export");
    };
  }

  private static ReportSpec spec(MultiValueMap<String, String> params) {
    if (!ReportSpecQueryString.isPresent(params)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No Report to export");
    }
    try {
      return ReportSpecQueryString.fromParams(params);
    } catch (IllegalArgumentException | IllegalStateException malformed) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a Report", malformed);
    }
  }

  /** The Report's name as a safe file name, {@code -raw} marking the raw form. */
  static String fileName(String name, boolean raw) {
    String base =
        name == null
            ? ""
            : name.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    return (base.isEmpty() ? "report" : base) + (raw ? "-raw" : "") + ".csv";
  }
}
