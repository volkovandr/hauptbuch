package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.ledger.TransactionDraft;

/**
 * Integration tier (plan §1.5): reviewing a {@code pending_review} row in the dock (register §2.10,
 * recurring plan slice d). Save confirms it even with no changes and even when it is future-dated —
 * the one-click confirm depends on an unchanged Save not being a no-op — and Cancel leaves it
 * pending.
 *
 * <p>Pending rows are seeded straight through the ledger, as the booking run leaves them. The
 * unchanged Save resubmits exactly what the edit dock rendered, scraped from its inputs, so the
 * test sees what the browser would send. {@code @Transactional} rolls each test back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterPendingReviewIntegrationTest {

  private static final String ENTRY_PATH = "/register/entry";
  private static final String EUR = "EUR";
  private static final String VIEW_PENDING_ONLY = "viewPendingOnly";
  private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>");
  private static final Pattern SELECT =
      Pattern.compile("(?s)<select\\b[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</select>");
  private static final Pattern SELECTED_OPTION =
      Pattern.compile("<option\\b[^>]*value=\"([^\"]*)\"[^>]*selected[^>]*>");

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  @Autowired LedgerService ledgerService;

  private long cash;
  private long food;
  private long transactionId;

  /**
   * Record a future-dated pending occurrence, like a booking run under {@code review}; the funding
   * leg comes first, as the dock books it.
   */
  private long pendingOccurrence(PostingDraft... legs) {
    return ledgerService.recordTransaction(
        TransactionDraft.pendingReview(
            LocalDate.now().plusDays(5), null, "occurrence", List.of(legs)));
  }

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
    cash =
        accountService
            .openAccount(
                new AccountDraft(
                    "Cash", "asset", null, EUR, LocalDate.of(2026, 1, 1), new BigDecimal("500")))
            .accountId();
    food = accountService.insertLeaf("Food", "expense", null, EUR).accountId();
    transactionId =
        pendingOccurrence(
            PostingDraft.of(cash, new BigDecimal("-20")),
            PostingDraft.of(food, new BigDecimal("20")));
  }

  private String lifecycle(long id) {
    return jdbcClient
        .sql("select lifecycle from transaction where transaction_id = :t")
        .param("t", id)
        .query(String.class)
        .single();
  }

  /**
   * Post {@code form} back to {@code path} exactly as it was prefilled — what a browser submits:
   * named inputs (ticked checkboxes only, never buttons) and each select's selected option.
   */
  private static MockHttpServletRequestBuilder resubmit(String form, String path) {
    MockHttpServletRequestBuilder request = post(path);
    Matcher input = INPUT.matcher(form);
    while (input.find()) {
      String tag = input.group();
      String type = attribute(tag, "type");
      String name = attribute(tag, "name");
      boolean unticked = "checkbox".equals(type) && attribute(tag, "checked") == null;
      if (name != null && !"button".equals(type) && !unticked) {
        String value = attribute(tag, "value");
        request.param(name, value == null ? "" : value);
      }
    }
    Matcher select = SELECT.matcher(form);
    while (select.find()) {
      Matcher option = SELECTED_OPTION.matcher(select.group(2));
      if (option.find()) {
        request.param(select.group(1), option.group(1));
      }
    }
    return request;
  }

  /** The tag's {@code name} attribute value, unescaped; null when the tag does not carry it. */
  private static String attribute(String tag, String name) {
    Matcher value = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(tag);
    return value.find() ? HtmlUtils.htmlUnescape(value.group(1)) : null;
  }

  @Test
  void savingPendingRowWithNoChangesConfirmsIt() throws Exception {
    String dock =
        mockMvc
            .perform(get("/register/edit/" + transactionId).param(VIEW_PENDING_ONLY, "true"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    mockMvc
        .perform(resubmit(dock, ENTRY_PATH))
        .andExpect(status().isOk())
        // Repainted under Pending only, the confirmed row has left the view.
        .andExpect(content().string(containsString("id=\"register-empty\"")));

    assertThat(lifecycle(transactionId)).isEqualTo("confirmed");
    assertThat(
            jdbcClient
                .sql("select amount from posting where transaction_id = :t order by amount")
                .param("t", transactionId)
                .query(BigDecimal.class)
                .list())
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal("-20"), new BigDecimal("20"));
  }

  @Test
  void savingPendingSplitWithNoChangesConfirmsIt() throws Exception {
    long drinks = accountService.insertLeaf("Drinks", "expense", null, EUR).accountId();
    long split =
        pendingOccurrence(
            PostingDraft.of(cash, new BigDecimal("-30")),
            PostingDraft.of(food, new BigDecimal("20")),
            PostingDraft.of(drinks, BigDecimal.TEN));
    String panel =
        mockMvc
            .perform(get("/register/edit/" + split).param(VIEW_PENDING_ONLY, "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/register/split/commit")))
            .andReturn()
            .getResponse()
            .getContentAsString();

    mockMvc.perform(resubmit(panel, "/register/split/commit")).andExpect(status().isOk());

    assertThat(lifecycle(split)).isEqualTo("confirmed");
  }

  @Test
  void cancellingPendingRowLeavesItPending() throws Exception {
    mockMvc.perform(get("/register/edit/" + transactionId)).andExpect(status().isOk());

    mockMvc
        .perform(get("/register/dock").param(VIEW_PENDING_ONLY, "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("New transaction")));

    assertThat(lifecycle(transactionId)).isEqualTo("pending_review");
  }
}
