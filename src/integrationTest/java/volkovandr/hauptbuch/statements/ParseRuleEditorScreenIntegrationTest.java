package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.statements.repository.StatementProfileRuleRepository;

/**
 * Integration tier (CLAUDE.md §6): the PDF (rules) profile editor (statements.md §3.2a) driven
 * through MockMvc against real Postgres — a profile saves with its rules and reopens with them, a
 * bad rule is refused with the form kept, the live check swaps each rule's problem in out of band,
 * the row buttons edit the list, a PDF's text loads into the test area without anything being
 * stored, and Try shows the TOON. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ParseRuleEditorScreenIntegrationTest {

  private static final String BOOKING_DATE = "^Buchungsdatum: (?<bookingDate>\\S+)$";
  private static final String BOOKING =
      "^(?<counterparty>.*) \\d\\d\\.\\d\\d (?<amount>[\\d.]+,\\d\\d-?)$";
  private static final String TEST_TEXT =
      "Buchungsdatum: 03.08.2026\nSHOPAAA SOMEWHERE 03.08 1.206,80-\nKartenzahlung\n";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired StatementProfileRuleRepository ruleRepository;

  /** The form's rules settings, German statement style. */
  private static MockHttpServletRequestBuilder withSettings(MockHttpServletRequestBuilder request) {
    return request
        .param("format", "pdf_rules")
        .param("windowDaysBefore", "10")
        .param("windowDaysAfter", "3")
        .param("rulesThousandsSeparator", ".")
        .param("rulesDecimalSeparator", ",")
        .param("rulesMinusPosition", "trailing")
        .param("rulesDateFormat", "dd.MM.yyyy")
        .param("rulesContinuation", "previous");
  }

  /** The two rules of the test text, as the form posts them. */
  private static MockHttpServletRequestBuilder withRules(MockHttpServletRequestBuilder request) {
    return request
        .param("ruleKind", "set", "booking")
        .param("rulePattern", BOOKING_DATE, BOOKING)
        .param("rulePatternEnd", "", "");
  }

  private long profileIdNamed(String name) {
    return jdbcClient
        .sql("select statement_profile_id from statement_profile where name = :name")
        .param("name", name)
        .query(Long.class)
        .single();
  }

  @Test
  void newRulesProfileEditorWiresTheLiveCheck() throws Exception {
    mockMvc
        .perform(get("/statements/profiles/new-pdf-rules"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("hx-post=\"/statements/profiles/rules/check\"")))
        .andExpect(content().string(containsString("hx-target=\"this\"")))
        .andExpect(content().string(containsString("novalidate=\"novalidate\"")))
        .andExpect(content().string(containsString("hx-params=\"not testText\"")))
        .andExpect(content().string(containsString("name=\"ruleKind\"")))
        .andExpect(content().string(containsString("name=\"testText\"")))
        .andExpect(content().string(not(containsString("name=\"aiNote\""))));
  }

  @Test
  void rulesEditorShowsEveryGroupNameToCopyAndTheCheatSheet() throws Exception {
    String page =
        mockMvc
            .perform(get("/statements/profiles/new-pdf-rules"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(page).contains("Regex cheat sheet");
    for (String field : ParseRule.HEADER_FIELDS) {
      assertThat(page).contains("(?&lt;" + field + "&gt;)");
    }
    for (String field : ParseRule.LINE_FIELDS) {
      assertThat(page).contains("(?&lt;" + field + "&gt;)");
    }
  }

  @Test
  void savesRulesProfileWithItsRulesAndReopensWithThem() throws Exception {
    mockMvc
        .perform(withRules(withSettings(post("/statements/profiles/save"))).param("name", "Bbb"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/statements/profiles"));

    long id = profileIdNamed("Bbb");
    assertThat(ruleRepository.findByProfile(id))
        .containsExactly(
            new ParseRule(ParseRule.SET, BOOKING_DATE, null),
            new ParseRule(ParseRule.BOOKING, BOOKING, null));
    mockMvc
        .perform(get("/statements/profiles/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Buchungsdatum: (?&lt;bookingDate&gt;")));
  }

  @Test
  void badRuleIsRefusedWithTheFormKept() throws Exception {
    mockMvc
        .perform(
            withSettings(post("/statements/profiles/save"))
                .param("name", "Broken")
                .param("ruleKind", "booking", "set")
                .param("rulePattern", BOOKING, "(?<bookingDate>")
                .param("rulePatternEnd", "", "")
                .param("testText", "kept test text"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Rule 2: Invalid regex")))
        .andExpect(content().string(containsString("(?&lt;bookingDate&gt;")))
        .andExpect(content().string(containsString("kept test text")))
        .andExpect(
            content()
                .string(
                    containsString("<p id=\"test-pdf-error\" class=\"statements__error\"></p>")));
    assertThat(
            jdbcClient
                .sql("select count(*) from statement_profile where name = 'Broken'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void liveCheckSwapsEachProblemInOutOfBand() throws Exception {
    mockMvc
        .perform(
            withSettings(post("/statements/profiles/rules/check"))
                .param("ruleKind", "set", "header")
                .param("rulePattern", BOOKING_DATE, "(?<amount>.*)")
                .param("rulePatternEnd", "", ""))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"rule-problem-1\"")))
        .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
        .andExpect(content().string(containsString("&#39;amount&#39; is not a field")))
        .andExpect(content().string(containsString("Add at least one booking rule.")));
  }

  @Test
  void liveCheckReportsNumberThatWillNotBind() throws Exception {
    mockMvc
        .perform(
            withRules(withSettings(post("/statements/profiles/rules/check")))
                .param("rulesSmartSpaceWidth", "abc"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Check the numbers")));
  }

  @Test
  void rowButtonsAddAndMoveRules() throws Exception {
    String added =
        mockMvc
            .perform(
                withRules(withSettings(post("/statements/profiles/rules/edit"))).param("op", "add"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(added.split("name=\"ruleKind\"", -1)).hasSize(4);

    String moved =
        mockMvc
            .perform(
                withRules(withSettings(post("/statements/profiles/rules/edit")))
                    .param("op", "up-1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(moved.indexOf("(?&lt;amount&gt;")).isLessThan(moved.indexOf("Buchungsdatum"));
  }

  @Test
  void tryShowsTheToonOfTheTestText() throws Exception {
    mockMvc
        .perform(
            withRules(withSettings(post("/statements/profiles/rules/try")))
                .param("testText", TEST_TEXT))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("lines[1]")))
        .andExpect(content().string(containsString("SHOPAAA SOMEWHERE")))
        .andExpect(content().string(containsString("-1206.80")))
        .andExpect(content().string(containsString("Kartenzahlung")));
  }

  @Test
  void tryWithoutTestTextAsksForIt() throws Exception {
    mockMvc
        .perform(withRules(withSettings(post("/statements/profiles/rules/try"))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Load a PDF or paste")));
  }

  @Test
  void pdfTextLoadsIntoTheTestAreaAndNothingIsStored() throws Exception {
    byte[] pdf = StatementPdfFixtures.pdf("Buchungsdatum: 03.08.2026", "SHOPAAA 03.08 1,00-");
    List<Long> before = rowCounts();

    mockMvc
        .perform(
            multipart("/statements/profiles/rules/test-text")
                .file(new MockMultipartFile("testPdf", "s.pdf", "application/pdf", pdf)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"testText\"")))
        .andExpect(content().string(containsString("Buchungsdatum: 03.08.2026")));
    assertThat(rowCounts()).isEqualTo(before);
  }

  private List<Long> rowCounts() {
    return List.of("statement", "statement_profile", "statement_profile_rule").stream()
        .map(table -> jdbcClient.sql("select count(*) from " + table).query(Long.class).single())
        .toList();
  }

  @Test
  void fileThatIsNotPdfIsRefused() throws Exception {
    mockMvc
        .perform(
            multipart("/statements/profiles/rules/test-text")
                .file(
                    new MockMultipartFile(
                        "testPdf",
                        "s.pdf",
                        "application/pdf",
                        "not a pdf".getBytes(StandardCharsets.UTF_8))))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"test-pdf-error\"")))
        .andExpect(content().string(not(containsString("id=\"testText\""))));
  }
}
