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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier: {@code hauptbuch.public-base-url} overrides the phone QR panel's URL (issue
 * landing-page/03). Its own class because it needs a different property set, and so a different
 * application context, from {@link LandingPhoneQrPanelIntegrationTest}.
 *
 * <p>The request here would resolve perfectly well on its own — the point is that it is not used.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "hauptbuch.public-base-url=http://homenet/pi/hauptbuch")
class LandingPhoneQrOverrideIntegrationTest {

  @Autowired MockMvc mockMvc;

  @Test
  void overrideWinsOverTheRequest() throws Exception {
    mockMvc
        .perform(get(URI.create("http://raspberrypi:8080/")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("<title>http://homenet/pi/hauptbuch/</title>"),
                        containsString(">http://homenet/pi/hauptbuch/</p>"),
                        not(containsString("raspberrypi")))));
  }
}
