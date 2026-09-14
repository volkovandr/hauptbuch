package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Integration tier (CLAUDE.md §6): the reporting page and its Layout ({@link
 * ReportsLayoutController}), reporting.md §11 / plan stage d2 — {@code /reports} rendering its
 * Frames read-only with no configuration inputs plus the My reports and Presets lists, {@code
 * /reports/layout} resizing the grid without persisting and {@code Save layout} persisting the
 * whole grid at once and navigating back to {@code /reports} via {@code HX-Redirect}, and a stale
 * Frame reference degrading rather than 500ing (mirroring {@link
 * MainFrameControllerIntegrationTest}'s bar for the main page's own Layout).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportsLayoutControllerIntegrationTest {

  private static final String REPORTS_PATH = "/reports";
  private static final String LAYOUT_PATH = "/reports/layout";
  private static final String RESIZE_PATH = "/reports/layout/resize";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;
  @Autowired ReportService reportService;

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
  void reportsPageShowsAnEmptyReportListWhenNoneAreSaved() throws Exception {
    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No saved reports yet")));
  }

  @Test
  void reportsPageListsEveryPreset() throws Exception {
    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("href=\"/reports/preset/balance-sheet\""),
                        containsString("Balance sheet"))));
  }

  @Test
  void reportsPageDefaultsToTheOneByOneEmptyLayoutWithNoConfigurationInputs() throws Exception {
    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(
            content().string(allOf(containsString("No report."), not(containsString("<select")))));
  }

  @Test
  void reportsPageOffersAnEditLayoutLink() throws Exception {
    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/reports/layout\"")));
  }

  @Test
  void editLayoutPageShowsTheGridWithDropdownsPerFrame() throws Exception {
    mockMvc
        .perform(get(LAYOUT_PATH))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(allOf(containsString("frame-0-0"), containsString("Choose a report"))));
  }

  @Test
  void editLayoutPageOffersCancelLinkBackToReports() throws Exception {
    mockMvc
        .perform(get(LAYOUT_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/reports\"")));
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
        .perform(get(LAYOUT_PATH))
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
                .param("frame-0-0", "preset:balance-sheet"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")));
  }

  @Test
  void saveLayoutPersistsShapeAndEveryFramesPresetAndNavigatesBackViaHxRedirect() throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(
            post(LAYOUT_PATH)
                .param("rowCount", "1")
                .param("columnCount", "2")
                .param("frame-0-0", "preset:balance-sheet")
                .param("frame-0-1", ""))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Redirect", REPORTS_PATH));

    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")));
  }

  @Test
  void configuredFrameLinksToThePresetsOwnPageWhereCopyToMyReportsLives() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedFrameSlug("balance-sheet");

    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/reports/preset/balance-sheet\"")));
  }

  @Test
  void unconfiguredFrameOffersNoOpenReportLink() throws Exception {
    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Open report"))));
  }

  @Test
  void pickerListsSavedReportsAlongsidePresets() throws Exception {
    reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc
        .perform(get(LAYOUT_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("My matrix")));
  }

  @Test
  void savingFrameWithSavedReportPersistsAndRenders() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My balance sheet", Presets.balanceSheet(), Renderer.TABLE, false);

    mockMvc
        .perform(
            post(LAYOUT_PATH)
                .param("rowCount", "1")
                .param("columnCount", "1")
                .param("frame-0-0", "report:" + saved.reportId()))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Redirect", REPORTS_PATH));

    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/reports/" + saved.reportId() + "\"")));
  }

  @Test
  void savingFrameWithUnknownReportIdIsRejected() {
    settingsService.setBaseCurrency("EUR");

    // Unhandled outside htmx (GlobalHtmxErrorAdvice re-throws for a non-htmx request), so the
    // rejection surfaces as a thrown exception from perform() itself, not a response status.
    assertThatThrownBy(
            () ->
                mockMvc.perform(
                    post(LAYOUT_PATH)
                        .param("rowCount", "1")
                        .param("columnCount", "1")
                        .param("frame-0-0", "report:999999")))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deletingReportReferencedByFrameDegradesItRatherThanFailingTheDelete() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("Disposable", Presets.balanceSheet(), Renderer.TABLE, false);
    mockMvc.perform(
        post(LAYOUT_PATH)
            .param("rowCount", "1")
            .param("columnCount", "1")
            .param("frame-0-0", "report:" + saved.reportId()));

    reportService.delete(saved.reportId());

    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No report.")));
  }

  @Test
  void staleFrameReferenceDegradesToEmptyFrameRatherThanFiveHundreding() throws Exception {
    settingsService.setBaseCurrency("EUR");
    seedFrameSlug("no-such-preset-any-more");

    mockMvc
        .perform(get(REPORTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No report.")));

    mockMvc
        .perform(get(LAYOUT_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Choose a report")));
  }
}
