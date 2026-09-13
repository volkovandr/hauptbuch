package volkovandr.hauptbuch.analytics.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access to the {@code layout} / {@code layout_frame} tables (reporting.md §11, plan
 * stage c). Only the main page's single Frame (layout 1, position 0,0) is read or written yet — the
 * reporting page's own Layout, and general row x column configuration, are stage c's second work
 * package.
 */
@Repository
public class LayoutRepository {

  private static final int MAIN_LAYOUT_ID = 1;

  private final JdbcClient jdbcClient;

  LayoutRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** The main page's configured Preset slug — empty when the Frame has been cleared. */
  public Optional<String> findMainFramePresetSlug() {
    return jdbcClient
        .sql(
            """
            select preset_slug from layout_frame
            where layout_id = :layoutId and row_position = 0 and col_position = 0
            """)
        .param("layoutId", MAIN_LAYOUT_ID)
        .query(String.class)
        .optional();
  }

  /** Points the main page's Frame at a different Preset. */
  public void updateMainFramePreset(String presetSlug) {
    jdbcClient
        .sql(
            """
            update layout_frame set preset_slug = :slug
            where layout_id = :layoutId and row_position = 0 and col_position = 0
            """)
        .param("slug", presetSlug)
        .param("layoutId", MAIN_LAYOUT_ID)
        .update();
  }
}
