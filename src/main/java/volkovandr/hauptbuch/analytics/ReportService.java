package volkovandr.hauptbuch.analytics;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.analytics.repository.ReportRepository;

/**
 * Saving, listing, updating and deleting Reports (reporting.md §14/§11a, plan stage d3). Presets
 * ({@link PresetCatalog}) are never rows here and so can never be deleted through this service —
 * {@link ReportEditorController#saveAsNew} is the one bridge from a Preset (or a not-yet-saved
 * {@code /reports/new} draft) to an owned, editable row, via the same {@link #save} every other
 * "Save as new report" uses.
 */
@Service
class ReportService {

  private static final Logger LOG = LoggerFactory.getLogger(ReportService.class);

  private final ReportRepository reportRepository;

  ReportService(ReportRepository reportRepository) {
    this.reportRepository = reportRepository;
  }

  /** Every saved Report, for the reporting page's list. */
  List<SavedReport> list() {
    return reportRepository.findAll();
  }

  Optional<SavedReport> find(long reportId) {
    return reportRepository.findById(reportId);
  }

  /**
   * Saves a new Report — a Preset's or a saved Report's "Save as new report" (reporting.md §11a.1),
   * and {@code /reports/new}'s first save alike; the caller already resolved whichever draft is in
   * play into {@code spec}. Rejected before the write if {@code name} is blank.
   */
  SavedReport save(String name, ReportSpec spec, Renderer renderer, boolean trendLine) {
    SavedReport saved = reportRepository.insert(requireName(name), spec, renderer, trendLine);
    LOG.info("Report saved: id={}, name={}", saved.reportId(), saved.name());
    return saved;
  }

  /**
   * Overwrites a saved Report's name, spec, renderer and trend line in place (reporting.md §11a.1's
   * Save — every Frame showing it follows, since Frames reference rather than copy). Rejected
   * before the write if {@code name} is blank or {@code reportId} is unknown.
   */
  void updateSpec(
      long reportId, String name, ReportSpec spec, Renderer renderer, boolean trendLine) {
    requireReport(reportId);
    String trimmed = requireName(name);
    reportRepository.update(reportId, trimmed, spec, renderer, trendLine);
    LOG.info("Report updated: id={}, name={}", reportId, trimmed);
  }

  void delete(long reportId) {
    reportRepository.delete(reportId);
    LOG.info("Report deleted: id={}", reportId);
  }

  private SavedReport requireReport(long reportId) {
    return reportRepository
        .findById(reportId)
        .orElseThrow(() -> new IllegalArgumentException("No report with id " + reportId));
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A Report needs a name.");
    }
    return name.strip();
  }
}
