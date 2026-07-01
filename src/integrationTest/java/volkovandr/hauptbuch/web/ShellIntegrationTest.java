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
 * template-logic unit test (CLAUDE.md §6) — it proves the shell wiring end to end: a feature screen
 * (the landing page) resolves the base layout, the navigation chrome is present, and the vendored
 * assets are served locally (no CDN). The screens' own behaviour is covered by the ledger settings
 * integration test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShellIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  void landingRendersInsideTheShellWithNavigationAndVendoredAssets() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        // Navigation chrome lists the top-level sections.
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Register")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Settings")))
        // Assets are vendored locally (no CDN).
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/htmx.min.js")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/keyboard.js")));
  }

  @Test
  void staticAssetsAreServed() throws Exception {
    mockMvc.perform(get("/js/htmx.min.js")).andExpect(status().isOk());
    mockMvc.perform(get("/js/keyboard.js")).andExpect(status().isOk());
    mockMvc.perform(get("/css/app.css")).andExpect(status().isOk());
  }
}
