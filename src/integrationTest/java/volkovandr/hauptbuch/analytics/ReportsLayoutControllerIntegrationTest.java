package volkovandr.hauptbuch.analytics;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): the reporting page's own Layout ({@link
 * ReportsLayoutController}), reporting.md §11 / plan stage c part 2 — the persisted 1x1 empty
 * default, resizing the grid without persisting, {@code Save layout} persisting the whole grid at
 * once, and a stale Frame reference degrading rather than 500ing (mirroring {@link
 * MainFrameControllerIntegrationTest}'s bar for the main page's own Layout).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportsLayoutControllerIntegrationTest {

  private static final String RESIZE_PATH = "/reports/layout/resize";
  private static final String SAVE_PATH = "/reports/layout";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  private void seedFrameSlug(String slug) {
    jdbcClient
        .sql(
            "update layout_frame set preset_slug = :slug "
                + "where layout_id = (select layout_id from layout where page = 'reports') "
                + "and row_position = 0 and col_position = 0")
        .param("slug", slug)
        .update();
  }

  @Test
  void reportsPageShowsThePlaceholderAndAnEmptyReportList() throws Exception {
    mockMvc
        .perform(get("/reports"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(containsString("New report"), containsString("No saved reports yet"))));
  }

  @Test
  void reportsPageDefaultsToTheOneByOneEmptyLayout() throws Exception {
    mockMvc
        .perform(get("/reports"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Choose a report"),
                        containsString("No report configured"))));
  }

  @Test
  void resizingGrowsTheGridWithoutPersisting() throws Exception {
    mockMvc
        .perform(
            post(RESIZE_PATH)
                .param("rowCount", "1")
                .param("columnCount", "2")
                .param("frame-0-0", ""))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("frame-0-1")));

    // Not persisted: a fresh GET still shows the seeded 1x1 shape.
    mockMvc
        .perform(get("/reports"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("frame-0-1"))));
  }

  @Test
  void resizingClampsNonPositiveRowOrColumnCountToOne() throws Exception {
    mockMvc
        .perform(post(RESIZE_PATH).param("rowCount", "0").param("columnCount", "-3"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(allOf(containsString("frame-0-0"), not(containsString("frame-0-1")))));
  }

  @Test
  void resizingCarriesOverAnExistingSelection() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(
            post(RESIZE_PATH)
                .param("rowCount", "1")
                .param("columnCount", "2")
                .param("frame-0-0", "balance-sheet"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")));
  }

  @Test
  void saveLayoutPersistsShapeAndEveryFramesPreset() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(
            post(SAVE_PATH)
                .param("rowCount", "1")
                .param("columnCount", "2")
                .param("frame-0-0", "balance-sheet")
                .param("frame-0-1", ""))
        .andExpect(status().isOk())
        .andExpect(content().string(allOf(containsString("<table"), containsString("frame-0-1"))));

    mockMvc
        .perform(get("/reports"))
        .andExpect(status().isOk())
        .andExpect(content().string(allOf(containsString("<table"), containsString("frame-0-1"))));
  }

  @Test
  void staleFrameReferenceDegradesToEmptyFrameRatherThanFiveHundreding() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedFrameSlug("no-such-preset-any-more");

    mockMvc
        .perform(get("/reports"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("No report configured"),
                        containsString("Choose a report"))));
  }
}
