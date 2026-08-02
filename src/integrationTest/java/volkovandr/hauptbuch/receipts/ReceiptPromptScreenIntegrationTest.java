package volkovandr.hauptbuch.receipts;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (§1.5): the stage-9e parse-prompt editor (owner feedback 2026-08-02), driven
 * through its controller. Proves the default instructions render, a saved override round-trips and
 * is reported as in effect, a reset restores the default, and the read-only category preview is
 * shown. {@code @Transactional} rolls the shared settings row back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptPromptScreenIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  void showsDefaultInstructionsAndCategoryPreview() throws Exception {
    mockMvc
        .perform(get("/receipts/ai-prompt"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("You extract structured data")))
        .andExpect(content().string(containsString("built-in default is in effect")))
        .andExpect(content().string(containsString("Categories:")));
  }

  @Test
  void savingAnOverrideRoundTripsAndIsReportedInEffect() throws Exception {
    mockMvc
        .perform(post("/receipts/ai-prompt").param("instructions", "My custom parse instructions"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("My custom parse instructions")))
        .andExpect(content().string(containsString("custom prompt is in effect")))
        // The default is no longer what's stored (though it stays available on reset).
        .andExpect(content().string(not(containsString("built-in default is in effect"))));
  }

  @Test
  void resetRestoresTheBuiltInDefault() throws Exception {
    mockMvc.perform(post("/receipts/ai-prompt").param("instructions", "Temporary override"));

    mockMvc
        .perform(post("/receipts/ai-prompt").param("reset", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("built-in default is in effect")))
        .andExpect(content().string(not(containsString("Temporary override"))));
  }
}
