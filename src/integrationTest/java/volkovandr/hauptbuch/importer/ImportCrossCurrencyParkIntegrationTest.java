package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.math.BigDecimal;
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
 * Integration tier (import.md §12): the cross-currency panel driven through {@link
 * ImportController} — the manual match (§6.5) and hand-entered far amount (§6.4) endpoints, and
 * their rendering on {@code /import/review}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportCrossCurrencyParkIntegrationTest {

  /**
   * One simple transfer, Current Account (EUR) → Franc Account (CHF). No mirror ever staged. The
   * day (28) is &gt; 12, so the file's date order is unambiguous DD/MM (import.md §4.3).
   */
  private static final String CURRENT_TO_FRANC =
      """
      !Type:Bank
      D28/07'2004
      T-100.00
      PTransfer to Franc
      L[Franc Account]
      ^
      """;

  /**
   * Two same-day transfers Current Account → Franc Account — ambiguous without a manual pick. The
   * day (28) is &gt; 12, so the file's date order is unambiguous DD/MM (import.md §4.3).
   */
  private static final String CURRENT_TO_FRANC_AMBIGUOUS =
      """
      !Type:Bank
      D28/06'2016
      T-100.00
      PTransferOut1
      L[Franc Account]
      ^
      D28/06'2016
      T-200.00
      PTransferOut2
      L[Franc Account]
      ^
      """;

  /** The mirror side of {@link #CURRENT_TO_FRANC_AMBIGUOUS}, also ambiguous. */
  private static final String FRANC_TO_CURRENT_AMBIGUOUS =
      """
      !Type:Bank
      D28/06'2016
      T150.00
      PTransferIn1
      L[Current Account]
      ^
      D28/06'2016
      T305.00
      PTransferIn2
      L[Current Account]
      ^
      """;

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
    String token = uploadOnly(session, file);
    mockMvc.perform(
        post("/import/uploads/" + token).param("moneyAccountName", account).session(session));
    return token;
  }

  private String uploadOnly(MockHttpSession session, MockMultipartFile file) throws Exception {
    MvcResult result =
        mockMvc.perform(multipart("/import/uploads").file(file).session(session)).andReturn();
    String location =
        Objects.requireNonNull(result.getResponse().getRedirectedUrl(), "no redirect Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  private void stageNewFile(MockHttpSession session, String name, String text, String account)
      throws Exception {
    String token = upload(session, qif(name, text), account);
    mockMvc
        .perform(post("/import/uploads/" + token + "/stage").session(session))
        .andExpect(redirectedUrl("/import"));
  }

  private long insertAccount(String name, String currencyCode) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, 'asset', :c)"
                + " returning account_id")
        .param("n", name)
        .param("c", currencyCode)
        .query(Long.class)
        .single();
  }

  private long mapRowId(String moneyAccountName) {
    return jdbcClient
        .sql("select import_account_id from import_account where money_account_name = :name")
        .param("name", moneyAccountName)
        .query(Long.class)
        .single();
  }

  private void mapToExisting(MockHttpSession session, String moneyAccountName, long accountId)
      throws Exception {
    mockMvc.perform(
        post("/import/review/accounts/" + mapRowId(moneyAccountName) + "/map")
            .param("accountId", String.valueOf(accountId))
            .session(session));
  }

  private void clearExpectFile(MockHttpSession session, String moneyAccountName) throws Exception {
    mockMvc.perform(
        post("/import/review/accounts/" + mapRowId(moneyAccountName) + "/expect-file")
            .param("expectFile", "false")
            .session(session));
  }

  private long postingId(String farMoneyAccountName, String amount) {
    return jdbcClient
        .sql(
            "select import_posting_id from import_posting"
                + " where money_account_name = :n and amount = :a")
        .param("n", farMoneyAccountName)
        .param("a", new BigDecimal(amount))
        .query(Long.class)
        .single();
  }

  private String stateOf(long importPostingId) {
    return jdbcClient
        .sql(
            "select t.state from import_transaction t"
                + " join import_posting p on p.import_transaction_id = t.import_transaction_id"
                + " where p.import_posting_id = :id")
        .param("id", importPostingId)
        .query(String.class)
        .single();
  }

  private BigDecimal counterAmountOf(long importPostingId) {
    return jdbcClient
        .sql("select counter_amount from import_posting where import_posting_id = :id")
        .param("id", importPostingId)
        .query(BigDecimal.class)
        .optional()
        .orElse(null);
  }

  private String reviewHtml(MockHttpSession session) throws Exception {
    return mockMvc
        .perform(get("/import/review").session(session))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void reviewShowsParkedCrossCurrencyTransfer() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", CURRENT_TO_FRANC, "Current Account");
    mapToExisting(session, "Current Account", insertAccount("Giro", "EUR"));
    mapToExisting(session, "Franc Account", insertAccount("Sparen", "CHF"));

    String html = reviewHtml(session);

    assertThat(html).contains("id=\"cross-currency\"");
    assertThat(html).contains("Current Account");
    assertThat(html).contains("Franc Account");
    assertThat(html).contains("Match with");
  }

  @Test
  void handEnteredFarAmountClosesTheParkOnceExpectFileIsCleared() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", CURRENT_TO_FRANC, "Current Account");
    mapToExisting(session, "Current Account", insertAccount("Giro", "EUR"));
    mapToExisting(session, "Franc Account", insertAccount("Sparen", "CHF"));
    long transferLeg = postingId("Franc Account", "100.00");
    assertThat(stateOf(transferLeg)).isEqualTo("parked");

    clearExpectFile(session, "Franc Account");

    mockMvc
        .perform(
            post("/import/review/cross-currency/" + transferLeg + "/close")
                .param("farAmount", "150,00")
                .session(session))
        .andExpect(redirectedUrl("/import/review#cross-currency"));

    assertThat(stateOf(transferLeg)).isEqualTo("ready");
    assertThat(counterAmountOf(transferLeg)).isEqualByComparingTo("150.00");
    // The section itself (not just its explanatory HTML comment) is gone once nothing is parked.
    assertThat(reviewHtml(session)).doesNotContain("id=\"cross-currency\"");
  }

  @Test
  void closingParkWithoutAmountIsRejectedWithFlashMessage() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", CURRENT_TO_FRANC, "Current Account");
    mapToExisting(session, "Current Account", insertAccount("Giro", "EUR"));
    mapToExisting(session, "Franc Account", insertAccount("Sparen", "CHF"));
    long transferLeg = postingId("Franc Account", "100.00");
    clearExpectFile(session, "Franc Account");

    mockMvc
        .perform(
            post("/import/review/cross-currency/" + transferLeg + "/close")
                .param("farAmount", "")
                .session(session))
        .andExpect(redirectedUrl("/import/review#cross-currency"))
        .andExpect(flash().attributeExists("error"));

    assertThat(stateOf(transferLeg)).isEqualTo("parked");
    assertThat(counterAmountOf(transferLeg)).isNull();
  }

  @Test
  void manualMatchResolvesOnePairOfAnAmbiguousSetAndLeavesTheRestParked() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", CURRENT_TO_FRANC_AMBIGUOUS, "Current Account");
    stageNewFile(session, "franc.qif", FRANC_TO_CURRENT_AMBIGUOUS, "Franc Account");
    mapToExisting(session, "Current Account", insertAccount("Giro", "EUR"));
    mapToExisting(session, "Franc Account", insertAccount("Sparen", "CHF"));
    long euroA = postingId("Franc Account", "100.00");
    long euroB = postingId("Franc Account", "200.00");
    long francA = postingId("Current Account", "-150.00");
    long francB = postingId("Current Account", "-305.00");
    assertThat(stateOf(euroA)).isEqualTo("parked");
    assertThat(stateOf(francA)).isEqualTo("parked");

    mockMvc
        .perform(
            post("/import/review/cross-currency/" + euroA + "/match")
                .param("mirrorPostingId", String.valueOf(francA))
                .session(session))
        .andExpect(redirectedUrl("/import/review#cross-currency"));

    assertThat(stateOf(euroA)).isEqualTo("ready");
    assertThat(stateOf(francA)).isEqualTo("mirrored");
    assertThat(counterAmountOf(euroA)).isEqualByComparingTo("150.00");
    // The remaining, still-ambiguous pair is untouched.
    assertThat(stateOf(euroB)).isEqualTo("parked");
    assertThat(stateOf(francB)).isEqualTo("parked");
  }

  @Test
  void manualMatchRejectsUnrelatedPairWithFlashMessage() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", CURRENT_TO_FRANC_AMBIGUOUS, "Current Account");
    stageNewFile(session, "franc.qif", FRANC_TO_CURRENT_AMBIGUOUS, "Franc Account");
    mapToExisting(session, "Current Account", insertAccount("Giro", "EUR"));
    mapToExisting(session, "Franc Account", insertAccount("Sparen", "CHF"));
    long euroA = postingId("Franc Account", "100.00");
    long euroB = postingId("Franc Account", "200.00");

    mockMvc
        .perform(
            post("/import/review/cross-currency/" + euroA + "/match")
                .param("mirrorPostingId", String.valueOf(euroB))
                .session(session))
        .andExpect(redirectedUrl("/import/review#cross-currency"))
        .andExpect(flash().attributeExists("error"));

    assertThat(stateOf(euroA)).isEqualTo("parked");
    assertThat(stateOf(euroB)).isEqualTo("parked");
  }

  @Test
  void withoutMoneyPostingSelectedTheMatchIsRejected() throws Exception {
    MockHttpSession session = openCampaign();
    stageNewFile(session, "current.qif", CURRENT_TO_FRANC, "Current Account");
    mapToExisting(session, "Current Account", insertAccount("Giro", "EUR"));
    mapToExisting(session, "Franc Account", insertAccount("Sparen", "CHF"));
    long transferLeg = postingId("Franc Account", "100.00");

    mockMvc
        .perform(
            post("/import/review/cross-currency/" + transferLeg + "/match")
                .param("mirrorPostingId", "")
                .session(session))
        .andExpect(redirectedUrl("/import/review#cross-currency"))
        .andExpect(flash().attributeExists("error"));

    assertThat(stateOf(transferLeg)).isEqualTo("parked");
  }
}
