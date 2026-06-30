package volkovandr.hauptbuch.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (plan §1.5): the UI shell renders inside the full application context. Not a
 * template-logic unit test (CLAUDE.md §6) — it proves the stage-4 wiring end to end: the controller
 * resolves the layout, the navigation chrome is present, the vendored assets are referenced, and
 * the htmx fragment endpoint returns just the fragment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShellControllerIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  void homeRendersTheShellWithNavigationAndVendoredAssets() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("The shell works")))
        // Navigation chrome lists the top-level sections.
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Register")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Settings")))
        // Assets are vendored locally (no CDN).
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/htmx.min.js")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/keyboard.js")));
  }

  @Test
  void demoFragmentReturnsOnlyTheFragment() throws Exception {
    mockMvc
        .perform(get("/demo/fragment"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Swapped from the server")))
        // A fragment, not a full page — no <head>/nav chrome.
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<head>"))));
  }

  @Test
  void staticAssetsAreServed() throws Exception {
    mockMvc.perform(get("/js/htmx.min.js")).andExpect(status().isOk());
    mockMvc.perform(get("/js/keyboard.js")).andExpect(status().isOk());
    mockMvc.perform(get("/css/app.css")).andExpect(status().isOk());
  }
}
