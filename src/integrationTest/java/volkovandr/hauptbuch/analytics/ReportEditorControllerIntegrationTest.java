package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Objects;
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
 * Integration tier (CLAUDE.md §6): {@code /reports/new} — a not-yet-saved Report's own page,
 * starting from the category × month matrix spec (reporting.md §11a.1, plan stage d3) — and {@code
 * POST /reports/save-as-new}, the one shared "Save as new report" endpoint every editor page posts
 * to ({@link ReportController}, {@link SavedReportController} and this page alike).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportEditorControllerIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired ReportService reportService;
  @Autowired SettingsService settingsService;

  @Test
  void newReportShowsThePromptBeforeTheBaseCurrencyIsSet() throws Exception {
    mockMvc
        .perform(get("/reports/new"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Settings")));
  }

  @Test
  void newReportStartsFromTheCategoryMonthMatrixSpecWithNoUnsavedMarkerOrDeleteButton()
      throws Exception {
    settingsService.setBaseCurrency("EUR");

    mockMvc
        .perform(get("/reports/new"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Save as new report")))
        .andExpect(content().string(not(containsString("Unsaved changes"))))
        .andExpect(content().string(not(containsString("Delete report"))))
        // The matrix Preset's own scope — proof the default spec, not an empty one, is showing.
        .andExpect(content().string(containsString("Expense, Income")));
  }

  @Test
  void draftInTheQueryStringOverridesTheDefaultSpecAndShowsTheUnsavedMarker() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> draft = ReportSpecQueryString.toParams(Presets.balanceSheet());

    mockMvc
        .perform(get("/reports/new").params(draft))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Unsaved changes")))
        .andExpect(content().string(containsString("Asset, Equity, Liability")));
  }

  @Test
  void saveAsNewCreatesReportAndRedirectsToIt() throws Exception {
    settingsService.setBaseCurrency("EUR");
    MultiValueMap<String, String> params = ReportSpecQueryString.toParams(Presets.balanceSheet());
    params.add("name", "My new report");
    params.add("renderer", "TABLE");
    params.add("trendLine", "false");

    String redirect =
        Objects.requireNonNull(
            mockMvc
                .perform(post("/reports/save-as-new").params(params))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/reports/*"))
                .andReturn()
                .getResponse()
                .getRedirectedUrl());

    long reportId = Long.parseLong(redirect.substring(redirect.lastIndexOf('/') + 1));
    SavedReport saved = reportService.find(reportId).orElseThrow();
    assertThat(saved.name()).isEqualTo("My new report");
    assertThat(saved.spec()).isEqualTo(Presets.balanceSheet());
    assertThat(saved.renderer()).isEqualTo(Renderer.TABLE);
    assertThat(saved.trendLine()).isFalse();
  }
}
