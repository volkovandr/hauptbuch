package volkovandr.hauptbuch.analytics.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.analytics.Renderer;
import volkovandr.hauptbuch.analytics.ReportSpec;
import volkovandr.hauptbuch.analytics.SavedReport;

/**
 * Native-SQL access to the {@code report} table (reporting.md §14, plan stage d): a Report is a
 * document, so the spec is carried as jsonb text cast at the SQL boundary ({@code spec::jsonb} on
 * the way in, {@code spec::text} on the way out) rather than left to the driver's own jsonb-to-Java
 * mapping — {@link ReportSpecJson} owns the text &lt;-&gt; {@link ReportSpec} conversion either
 * side of that cast.
 */
@Repository
public class ReportRepository {

  private final JdbcClient jdbcClient;

  ReportRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Inserts a new Report and returns it with its generated id. */
  public SavedReport insert(String name, ReportSpec spec, Renderer renderer, boolean trendLine) {
    long reportId =
        jdbcClient
            .sql(
                """
                insert into report (name, renderer, trend_line, spec)
                values (:name, :renderer, :trendLine, :spec::jsonb)
                returning report_id
                """)
            .param("name", name)
            .param("renderer", renderer.name())
            .param("trendLine", trendLine)
            .param("spec", ReportSpecJson.toJson(spec))
            .query(Long.class)
            .single();
    return new SavedReport(reportId, name, spec, renderer, trendLine);
  }

  /** The Report at {@code reportId}, or empty when no such row exists. */
  public Optional<SavedReport> findById(long reportId) {
    return jdbcClient
        .sql(
            """
            select report_id, name, renderer, trend_line, spec::text as spec
            from report where report_id = :id
            """)
        .param("id", reportId)
        .query(RawRow.class)
        .optional()
        .map(ReportRepository::toSavedReport);
  }

  /** Every saved Report, alphabetically — the reporting page's list (reporting.md §14). */
  public List<SavedReport> findAll() {
    return jdbcClient
        .sql(
            """
            select report_id, name, renderer, trend_line, spec::text as spec
            from report order by name
            """)
        .query(RawRow.class)
        .list()
        .stream()
        .map(ReportRepository::toSavedReport)
        .toList();
  }

  /** Renames a Report in place. */
  public void rename(long reportId, String name) {
    jdbcClient
        .sql("update report set name = :name where report_id = :id")
        .param("name", name)
        .param("id", reportId)
        .update();
  }

  /** Deletes a Report. A no-op if {@code reportId} names no row. */
  public void delete(long reportId) {
    jdbcClient.sql("delete from report where report_id = :id").param("id", reportId).update();
  }

  private static SavedReport toSavedReport(RawRow row) {
    return new SavedReport(
        row.reportId(),
        row.name(),
        ReportSpecJson.fromJson(row.spec()),
        Renderer.valueOf(row.renderer()),
        row.trendLine());
  }

  private record RawRow(
      long reportId, String name, String renderer, boolean trendLine, String spec) {}
}
