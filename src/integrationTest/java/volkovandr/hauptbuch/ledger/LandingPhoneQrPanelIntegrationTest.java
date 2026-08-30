package volkovandr.hauptbuch.ledger;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (plan §1.5): the landing page's phone QR panel (CONTEXT.md "Phone QR panel",
 * issue landing-page/03) driven through {@link LandingController}.
 *
 * <p>The gateway case is why this test exists at this tier at all: sending real {@code
 * X-Forwarded-*} headers through the full filter chain is the only thing that proves {@code
 * server.forward-headers-strategy: framework} is actually wired, not merely written in the yaml.
 * The resolver's own decision table is unit-tested in {@code PublicBaseUrlResolverTest}.
 *
 * <p>Requests are built from a <em>full</em> URI, not a path plus a {@code Host} header: MockMvc
 * populates scheme/host/port from the URI and does not read them back off a header, so a bare
 * {@code get("/")} is always {@code localhost} — which is exactly the loopback case below.
 *
 * <p>No {@code @Transactional} and no seeding: the panel reads the request and config only, never
 * the book, and renders regardless of whether the base currency is set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// The literal addresses below are the request fixtures under test, not endpoints this code calls.
@SuppressWarnings("PMD.AvoidUsingHardCodedIP")
class LandingPhoneQrPanelIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  void rendersTheQrAndTheUrlForLanRequest() throws Exception {
    mockMvc
        .perform(get(URI.create("http://raspberrypi:8080/")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Open on your phone"),
                        containsString("<svg"),
                        containsString("<title>http://raspberrypi:8080/</title>"),
                        containsString(">http://raspberrypi:8080/</p>"))));
  }

  @Test
  void keepsOnlyTheOriginOfRequestCarryingPathAndQuery() throws Exception {
    mockMvc
        .perform(get(URI.create("http://192.168.1.14:8080/?desktop")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<title>http://192.168.1.14:8080/</title>")));
  }

  @Test
  void omitsThePanelEntirelyForLoopbackRequest() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Open on your phone"))))
        .andExpect(content().string(not(containsString("<svg"))));
  }

  @Test
  void foldsTheGatewayHostAndPathPrefixIntoTheUrl() throws Exception {
    mockMvc
        .perform(
            get(URI.create("http://10.0.0.5:8080/"))
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Host", "homenet")
                .header("X-Forwarded-Prefix", "/pi/hauptbuch"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("<title>http://homenet/pi/hauptbuch/</title>"),
                        containsString(">http://homenet/pi/hauptbuch/</p>"),
                        not(containsString("10.0.0.5")))));
  }
}
