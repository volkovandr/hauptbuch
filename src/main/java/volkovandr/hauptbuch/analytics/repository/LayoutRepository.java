package volkovandr.hauptbuch.analytics.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access to the {@code layout} / {@code layout_frame} tables (reporting.md §11, plan
 * stage c): a page's Layout, identified by its {@code page} ({@code "main"} or {@code "reports"}),
 * as one grid-dimensions-plus-Frames read, and a whole-Layout replace for {@code Save layout}.
 */
@Repository
public class LayoutRepository {

  private final JdbcClient jdbcClient;

  LayoutRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** The named page's Layout — every seeded page always has one (V25/V26). */
  public Optional<LayoutSnapshot> findByPage(String page) {
    Optional<LayoutDimensions> dimensions =
        jdbcClient
            .sql("select layout_id, row_count, column_count from layout where page = :page")
            .param("page", page)
            .query(LayoutDimensions.class)
            .optional();
    if (dimensions.isEmpty()) {
      return Optional.empty();
    }
    List<LayoutFrameRow> frames =
        jdbcClient
            .sql(
                """
                select row_position, col_position, preset_slug from layout_frame
                where layout_id = :layoutId
                order by row_position, col_position
                """)
            .param("layoutId", dimensions.get().layoutId())
            .query(LayoutFrameRow.class)
            .list();
    return Optional.of(
        new LayoutSnapshot(dimensions.get().rowCount(), dimensions.get().columnCount(), frames));
  }

  /**
   * Replaces the named page's Layout wholesale — its grid dimensions and every Frame's Preset — the
   * one {@code Save layout} action (reporting.md §11). Frame counts are small (no cap, but a human
   * configures this by hand), so a delete-then-reinsert is simpler to reason about than diffing the
   * old and new grids.
   */
  public void save(String page, int rowCount, int columnCount, List<LayoutFrameRow> frames) {
    long layoutId =
        jdbcClient
            .sql("select layout_id from layout where page = :page")
            .param("page", page)
            .query(Long.class)
            .single();
    jdbcClient
        .sql("update layout set row_count = :rows, column_count = :columns where layout_id = :id")
        .param("rows", rowCount)
        .param("columns", columnCount)
        .param("id", layoutId)
        .update();
    jdbcClient.sql("delete from layout_frame where layout_id = :id").param("id", layoutId).update();
    for (LayoutFrameRow frame : frames) {
      jdbcClient
          .sql(
              """
              insert into layout_frame (layout_id, row_position, col_position, preset_slug)
              values (:id, :row, :col, :slug)
              """)
          .param("id", layoutId)
          .param("row", frame.rowPosition())
          .param("col", frame.colPosition())
          .param("slug", frame.presetSlug())
          .update();
    }
  }

  private record LayoutDimensions(long layoutId, int rowCount, int columnCount) {}
}
