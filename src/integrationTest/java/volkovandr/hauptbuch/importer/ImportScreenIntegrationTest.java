package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (import.md §12): the {@code /import} screen driven through its controller —
 * upload → preview → override, and the same-name replacement-or-coincidence prompt. Asserts the b2
 * contract that <strong>nothing is written to any staging table</strong>.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportScreenIntegrationTest {

  private static final String DAY_MONTH_BANK =
      """
      !Type:Bank
      D01/07'2004
      T-12.34
      PGrocer
      LFood
      ^
      D28/07'2004
      T-5.00
      PBaker
      LFood
      ^
      """;

  private static final String INVESTMENT = "!Type:Invst\nD01/07'2004\n^\n";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;

  private static MockMultipartFile qif(String name, String text) {
    return new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8));
  }

  private MockHttpSession openCampaign() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc.perform(post("/import/session").session(session)).andExpect(redirectedUrl("/import"));
    return session;
  }

  private String upload(MockHttpSession session, MockMultipartFile file, String account)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                multipart("/import/uploads")
                    .file(file)
                    .param("moneyAccountName", account)
                    .session(session))
            .andExpect(redirectedUrlPattern("/import/uploads/*"))
            .andReturn();
    String location =
        Objects.requireNonNull(result.getResponse().getRedirectedUrl(), "no redirect Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  @Test
  void withoutCampaignTheScreenOffersToStartOne() throws Exception {
    mockMvc
        .perform(get("/import").session(new MockHttpSession()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Start an import session")));
  }

  @Test
  void uploadRoutesToPreviewOfParsedFile() throws Exception {
    MockHttpSession session = openCampaign();

    String token = upload(session, qif("export.qif", DAY_MONTH_BANK), "Current Account");

    mockMvc
        .perform(get("/import/uploads/" + token).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Current Account")))
        .andExpect(content().string(containsString("asset")))
        .andExpect(content().string(containsString("D28/07")))
        .andExpect(content().string(containsString(">day_month<")))
        .andExpect(content().string(containsString("!Type:Bank")));

    assertNothingStaged();
  }

  @Test
  void overrideChangesTheEffectiveCharsetAndDateOrder() throws Exception {
    MockHttpSession session = openCampaign();
    String token = upload(session, qif("export.qif", DAY_MONTH_BANK), "Current Account");

    mockMvc
        .perform(
            post("/import/uploads/" + token)
                .param("charset", "windows_1252")
                .param("dateOrder", "month_day")
                .session(session))
        .andExpect(redirectedUrl("/import/uploads/" + token));

    mockMvc
        .perform(get("/import/uploads/" + token).session(session))
        .andExpect(content().string(containsString(">windows_1252<")))
        .andExpect(content().string(containsString(">month_day<")));

    assertNothingStaged();
  }

  @Test
  void rejectedFileShowsItsReasonInThePreview() throws Exception {
    MockHttpSession session = openCampaign();

    String token = upload(session, qif("brokerage.qif", INVESTMENT), "Brokerage");

    mockMvc
        .perform(get("/import/uploads/" + token).session(session))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("investment")));

    assertNothingStaged();
  }

  @Test
  void secondFileOfSameNamePromptsReplacementOrCoincidence() throws Exception {
    MockHttpSession session = openCampaign();
    upload(session, qif("export.qif", DAY_MONTH_BANK), "Current Account");

    mockMvc
        .perform(
            multipart("/import/uploads")
                .file(qif("export.qif", DAY_MONTH_BANK))
                .param("moneyAccountName", "Savings")
                .session(session))
        .andExpect(redirectedUrl("/import"));

    mockMvc
        .perform(get("/import").session(session))
        .andExpect(content().string(containsString("replacement")))
        .andExpect(content().string(containsString("Keep both")));

    // Replace: the old upload is dropped, one pending upload remains.
    MvcResult resolved =
        mockMvc
            .perform(post("/import/uploads/clash").param("resolution", "replace").session(session))
            .andExpect(redirectedUrlPattern("/import/uploads/*"))
            .andReturn();

    mockMvc
        .perform(get("/import").session(session))
        .andExpect(content().string(containsString("Savings")))
        .andReturn();

    assertNothingStaged();
    assertThat(resolved.getResponse().getRedirectedUrl()).startsWith("/import/uploads/");
  }

  private void assertNothingStaged() {
    for (String table :
        new String[] {
          "import_file",
          "import_transaction",
          "import_posting",
          "import_account",
          "import_category",
          "import_category_tag"
        }) {
      assertThat(count(table)).as(table).isZero();
    }
  }

  private int count(String table) {
    return jdbcClient.sql("select count(*) from " + table).query(Integer.class).single();
  }
}
