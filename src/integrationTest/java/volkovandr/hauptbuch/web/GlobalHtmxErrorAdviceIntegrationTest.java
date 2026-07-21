package volkovandr.hauptbuch.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (plan §1.5): the global htmx error boundary (issue 10). An <em>unexpected</em>
 * exception thrown while servicing a controller request must, for an htmx request, become a visible
 * error retargeted to the shell's dedicated error slot — never a swap into (and blanking of) the
 * element that triggered the request — while a non-htmx request keeps the default whole-page error
 * behaviour. Uses a test-only endpoint that always throws, since no production path reliably raises
 * an unexpected error (the brief sanctions this).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestcontainersConfiguration.class,
  GlobalHtmxErrorAdviceIntegrationTest.BoomController.class
})
class GlobalHtmxErrorAdviceIntegrationTest {

  private static final String BOOM_PATH = "/test-error-endpoint/boom";
  private static final String SECRET = "internal detail that must never leak to the user";

  @Autowired MockMvc mockMvc;

  @Test
  void htmxPostGetsRetargetedInlineErrorInsteadOfBlankingTheTrigger() throws Exception {
    // The real-world scenario is a dock/split commit (an htmx POST) that throws unexpectedly.
    mockMvc
        .perform(post(BOOM_PATH).header("HX-Request", "true"))
        // 200 so htmx performs the swap at all (2.0.4 does not swap 4xx/5xx), but retargeted away
        // from the triggering element to the shell's dedicated error slot with an innerHTML swap —
        // the server-side contract that leaves the triggering element (register/dock) untouched.
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Retarget", "#app-error"))
        .andExpect(header().string("HX-Reswap", "innerHTML"))
        // A user-facing message is shown; the raw exception detail is not leaked.
        .andExpect(content().string(containsString("Something went wrong")))
        .andExpect(content().string(not(containsString(SECRET))));
  }

  @Test
  void nonHtmxRequestKeepsTheDefaultWholePageErrorBehaviour() {
    // The boundary engages only for htmx requests; a plain request lets the exception propagate to
    // the container's default error handling exactly as before (existing behaviour, unaffected).
    assertThatThrownBy(() -> mockMvc.perform(post(BOOM_PATH)))
        .hasRootCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void theShellCarriesTheErrorSlotOnEveryPage() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"app-error\"")));
  }

  /** Test-only controller whose sole endpoint always throws an unexpected exception. */
  @Controller
  static class BoomController {
    @PostMapping(BOOM_PATH)
    String boom() {
      throw new IllegalStateException(SECRET);
    }
  }
}
