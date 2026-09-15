package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import org.springframework.util.MultiValueMap;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (CLAUDE.md §6): a saved Report's own page and editor (reporting.md §14/§11a,
 * plan stage d3) — a draft URL renders its spec with the Unsaved marker, no parameters renders the
 * saved spec, Save overwrites in place, Discard points back to the bare URL, and Delete removes the
 * row. "Save as new report" is {@link ReportEditorControllerIntegrationTest}'s job, since it is one
 * shared endpoint every editor page posts to.
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
  void savedReportRendersItsOwnNameAndActionsWithNoUnsavedMarker() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My matrix", Presets.categoryMonthMatrix(), Renderer.TABLE, false);

    mockMvc
        .perform(get("/reports/" + saved.reportId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("My matrix")))
        .andExpect(content().string(containsString("Save")))
        .andExpect(content().string(containsString("Save as new report")))
        .andExpect(content().string(containsString("Delete report")))
        .andExpect(content().string(not(containsString("Unsaved changes"))));
  }

  @Test
  void draftInTheQueryStringOverridesTheSavedSpecAndShowsTheUnsavedMarker() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My balance sheet", Presets.balanceSheet(), Renderer.TABLE, false);
    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Unsaved changes")))
        // The matrix's own scope ("Expense, Income") rather than the balance sheet's ("Asset,
        // Equity, Liability") is the proof the draft, not the saved spec, rendered.
        .andExpect(content().string(containsString("Expense, Income")));
  }

  @Test
  void discardChangesLinksBackToTheBareUrl() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My balance sheet", Presets.balanceSheet(), Renderer.TABLE, false);
    MultiValueMap<String, String> draft =
        ReportSpecQueryString.toParams(Presets.categoryMonthMatrix());

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(content().string(containsString("href=\"/reports/" + saved.reportId() + "\"")));
  }

  @Test
  void saveOverwritesTheSpecRendererAndTrendLineAndRedirectsBackToTheSameReport() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> newSpec =
        ReportSpecQueryString.toParams(Presets.netWorthOverTime());
    newSpec.add("name", "New name");
    newSpec.add("renderer", "LINE");
    newSpec.add("trendLine", "true");
    SavedReport saved =
        reportService.save("Old name", Presets.balanceSheet(), Renderer.TABLE, false);

    mockMvc
        .perform(post("/reports/" + saved.reportId() + "/save").params(newSpec))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/reports/" + saved.reportId()));

    SavedReport updated = reportService.find(saved.reportId()).orElseThrow();
    assertThat(updated.name()).isEqualTo("New name");
    assertThat(updated.spec()).isEqualTo(Presets.netWorthOverTime());
    assertThat(updated.renderer()).isEqualTo(Renderer.LINE);
    assertThat(updated.trendLine()).isTrue();
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
  void anHtmxRequestGetsBackJustTheReportPageFragmentNotTheShell() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My chart", Presets.netWorthOverTime(), Renderer.LINE, true);

    mockMvc
        .perform(get("/reports/" + saved.reportId()).header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<svg")))
        .andExpect(content().string(not(containsString("app-bar__brand"))));
  }

  @Test
  void switchingTheRendererToTableRendersTheTableInstead() throws Exception {
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My chart", Presets.netWorthOverTime(), Renderer.LINE, true);
    MultiValueMap<String, String> draft =
        PresetRendering.allParams(
            new PresetRendering.Presentation(
                "x", Presets.netWorthOverTime(), Renderer.TABLE, false));

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<table")))
        .andExpect(content().string(containsString("Unsaved changes")));
  }

  @Test
  void draftWithTrendLineExplicitlyFalseTurnsTheOverlayOff() throws Exception {
    // What the browser actually submits when the Trend line checkbox is unticked (report-settings
    // .html: a hidden trendLine=false fallback sits right after the checkbox so an unticked box's
    // own field, which a browser omits, does not leave the last-saved true value in place).
    settingsService.setBaseCurrency("EUR");
    SavedReport saved =
        reportService.save("My chart", Presets.netWorthOverTime(), Renderer.LINE, true);
    MultiValueMap<String, String> draft =
        PresetRendering.allParams(
            new PresetRendering.Presentation(
                "x", Presets.netWorthOverTime(), Renderer.LINE, false));

    mockMvc
        .perform(get("/reports/" + saved.reportId()).params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("chart-trend"))));
  }
}
