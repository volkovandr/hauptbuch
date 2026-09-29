package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;

/**
 * Integration tier (plan §1.5): the main page's "N pending to review" line (register §2.3,
 * recurring plan slice d) — it counts the live pending rows, calls out the overdue ones, is hidden
 * at zero, and its link opens the register on exactly the rows it counted, however old.
 *
 * <p>{@code @Transactional} rolls each test back, including the write-once base currency.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class LandingPendingReviewIntegrationTest {

  private static final Pattern PENDING_LINK =
      Pattern.compile("href=\"([^\"]*pendingOnly=true[^\"]*)\"");

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired LedgerService ledgerService;
  @Autowired SettingsService settingsService;

  private long cash;
  private long food;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency("EUR");
    cash =
        accountService
            .openAccount(
                new AccountDraft(
                    "Cash", "asset", null, "EUR", LocalDate.now().minusYears(3), BigDecimal.ZERO))
            .accountId();
    food = accountService.insertLeaf("Food", "expense", null, "EUR").accountId();
  }

  private List<PostingDraft> legs(String magnitude) {
    return List.of(
        PostingDraft.of(cash, new BigDecimal("-" + magnitude)),
        PostingDraft.of(food, new BigDecimal(magnitude)));
  }

  private void pending(LocalDate date, String magnitude) {
    ledgerService.recordTransaction(
        TransactionDraft.pendingReview(date, null, "occurrence", legs(magnitude)));
  }

  private void confirmed(LocalDate date, String magnitude) {
    ledgerService.recordTransaction(
        new TransactionDraft(date, null, "spend", "confirmed", legs(magnitude)));
  }

  @Test
  void countsThePendingRowsCallsOutTheOverdueOnesAndLinksToExactlyThem() throws Exception {
    LocalDate today = LocalDate.now();
    // Older than the register's default 12-month range: the link must still reach it.
    pending(today.minusMonths(14), "11.11");
    pending(today.plusDays(3), "22.22");
    confirmed(today.minusDays(1), "33.33");

    String landing =
        mockMvc
            .perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(
                content()
                    .string(
                        allOf(containsString("2 pending to review"), containsString("1 overdue"))))
            .andReturn()
            .getResponse()
            .getContentAsString();

    Matcher link = PENDING_LINK.matcher(landing);
    assertThat(link.find()).isTrue();
    mockMvc
        .perform(get(HtmlUtils.htmlUnescape(link.group(1))))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("11,11"),
                        containsString("22,22"),
                        not(containsString("33,33")))));
  }

  @Test
  void noOverdueCalloutWhenEveryPendingRowIsTodayOrLater() throws Exception {
    pending(LocalDate.now(), "22.22");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(containsString("1 pending to review"), not(containsString("overdue")))));
  }

  @Test
  void hiddenWhenNothingIsPending() throws Exception {
    confirmed(LocalDate.now(), "33.33");

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("pending to review"))));
  }
}
