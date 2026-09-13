package volkovandr.hauptbuch.analytics;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.analytics.repository.ReportRepository;

/**
 * Saving, listing, renaming, duplicating and deleting Reports (reporting.md §14, plan stage d).
 * Presets ({@link PresetCatalog}) are never rows here and so can never be deleted through this
 * service — {@link #copyFromPreset} is the one bridge between the two, cloning a Preset's spec into
 * an owned, editable row. Resolving a Preset slug to a {@link PresetDef} is the caller's job (the
 * catalog is a web-facing, code-defined concept this service does not otherwise need to know
 * about); this only ever clones the tuple it is handed.
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

  /** Saves a new Report. Rejected before the write if {@code name} is blank. */
  SavedReport save(String name, ReportSpec spec, Renderer renderer, boolean trendLine) {
    SavedReport saved = reportRepository.insert(requireName(name), spec, renderer, trendLine);
    LOG.info("Report saved: id={}, name={}", saved.reportId(), saved.name());
    return saved;
  }

  /** Clones {@code preset}'s spec into a new owned Report named {@code name}. */
  SavedReport copyFromPreset(PresetDef preset, String name) {
    return save(name, preset.spec(), preset.renderer(), preset.trendLine());
  }

  /** Clones an existing Report's spec into a new one named {@code name}. */
  SavedReport duplicate(long reportId, String name) {
    SavedReport source = requireReport(reportId);
    return save(name, source.spec(), source.renderer(), source.trendLine());
  }

  /** Renames a Report in place. Rejected before the write if {@code name} is blank. */
  void rename(long reportId, String name) {
    requireReport(reportId);
    String trimmed = requireName(name);
    reportRepository.rename(reportId, trimmed);
    LOG.info("Report renamed: id={}, name={}", reportId, trimmed);
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
