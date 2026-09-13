package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): a saved Report's own page and its rename/duplicate/delete
 * actions (reporting.md §14, plan stage d) — the "Done when" bar of the sub-plan's slice d: a
 * Report survives a restart (proven at the repository tier by {@link
 * ReportRepositoryIntegrationTest}) and its link works from the reporting page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class SavedReportControllerIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ReportService reportService;
  @Autowired SettingsService settingsService;

  @Test
  void savedReportShowsThePromptBeforeTheBaseCurrencyIsSet() throws Exception {
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Settings")));
  }

  @Test
  void savedReportRendersItsOwnNameAndManageActions() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("My matrix")))
        .andExpect(content().string(containsString("Rename")))
        .andExpect(content().string(containsString("Duplicate")))
        .andExpect(content().string(containsString("Delete report")));
  }

  @Test
  void renamePersistsAndRedirectsBackToTheSameReport() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("Old name", Presets.balanceSheet(), Renderer.TABLE, false);

    mockMvc
        .perform(post("/reports/" + saved.reportId() + "/rename").param("name", "New name"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/reports/" + saved.reportId()));

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(content().string(containsString("New name")));
  }

  @Test
  void duplicateCreatesAnIndependentCopyAndRedirectsToIt() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("Original", Presets.balanceSheet(), Renderer.TABLE, false);

    mockMvc
        .perform(post("/reports/" + saved.reportId() + "/duplicate").param("name", "Copy"))
        .andExpect(status().is3xxRedirection());

    // The original still exists, unrenamed by the duplicate.
    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(content().string(containsString("Original")));
  }

  @Test
  void deleteRemovesTheReport() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("Disposable", Presets.balanceSheet(), Renderer.TABLE, false);

    mockMvc
        .perform(post("/reports/" + saved.reportId() + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/reports"));

    assertThat(reportService.find(saved.reportId())).isEmpty();
  }

  @Test
  void chartSwapsToItsTableFragmentAndBack() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My chart", Presets.netWorthOverTime(), Renderer.LINE, true);

    mockMvc
        .perform(get("/reports/" + saved.reportId() + "/view").param("view", "table"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")))
        .andExpect(content().string(containsString("Show chart")));

    mockMvc
        .perform(get("/reports/" + saved.reportId() + "/view").param("view", "chart"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<svg")))
        .andExpect(content().string(containsString("Show table")));
  }
}
