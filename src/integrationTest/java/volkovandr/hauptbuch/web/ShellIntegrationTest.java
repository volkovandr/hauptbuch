package volkovandr.hauptbuch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.ResourceUrlProvider;
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

  /** A content-hashed asset link, e.g. {@code /js/keyboard-8f3c….js}. */
  private static final Pattern HASHED_KEYBOARD_JS =
      Pattern.compile("/js/keyboard-[0-9a-f]{32}\\.js");

  @Autowired MockMvc mockMvc;
  @Autowired ResourceUrlProvider resourceUrlProvider;

  @Test
  void landingRendersInsideTheShellWithNavigationAndVendoredAssets() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        // Navigation chrome lists the top-level sections.
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Register")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Settings")))
        // Assets are vendored locally (no CDN) — and content-hashed, see the cache-busting test.
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/htmx.min-")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/keyboard-")));
  }

  @Test
  void staticAssetsAreServed() throws Exception {
    mockMvc.perform(get("/js/htmx.min.js")).andExpect(status().isOk());
    mockMvc.perform(get("/js/keyboard.js")).andExpect(status().isOk());
    mockMvc.perform(get("/css/base.css")).andExpect(status().isOk());
    mockMvc.perform(get("/css/shell.css")).andExpect(status().isOk());
    mockMvc.perform(get("/css/accounts.css")).andExpect(status().isOk());
    mockMvc.perform(get("/css/register.css")).andExpect(status().isOk());
  }

  /**
   * Cache-busting (application.yaml {@code spring.web.resources}): the shell links every asset
   * through a content hash, and the hashed URL is served with a one-year immutable cache. Without
   * this the app sends no Cache-Control at all and a browser is free to keep running yesterday's
   * script after a deploy — which is exactly how a fixed receipt editor kept saving colour scans on
   * the Pi (issue 12 regression).
   */
  @Test
  void assetsAreContentHashedAndCachedForever() throws Exception {
    String hashedUrl = resourceUrlProvider.getForLookupPath("/js/keyboard.js");

    assertThat(hashedUrl).matches(HASHED_KEYBOARD_JS);

    mockMvc
        .perform(get(hashedUrl))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "max-age=31536000, public"));
  }
}
