package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Integration tier (CLAUDE.md §6): editing, ending and deleting a template once it has booked rows
 * (data-model §14.3, recurring sub-plan slice e), through MockMvc against real Postgres. The
 * edit-by-edit rules are {@link RecurringRebookTest}'s; this covers the screens: a save replaces
 * the future pending rows at once, and an end date that cuts pending rows off, or a delete, asks
 * the operator what becomes of them.
 *
 * <p>Each test starts from a weekly {@code review} template that began 15 days ago, booked 14 days
 * ahead: pending rows 15, 8 and 1 days ago, and 6 and 13 days ahead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RecurringEditBookedIntegrationTest {

  private static final String EUR = "EUR";
  private static final String OLD_AMOUNT = "9,99";
  private static final String NEW_AMOUNT = "12,99";
  private static final String PENDING_ROWS = "pendingRows";
  private static final String DELETE_ANSWER = "deleteAnswer";
  private static final LocalDate TODAY = LocalDate.now();

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired RecurringTemplateRepository repository;
  @Autowired JdbcClient jdbcClient;

  private long bankId;
  private long streamingId;
  private long templateId;

  @BeforeEach
  void setUp() throws Exception {
    settingsService.setBaseCurrency(EUR);
    bankId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankAaa-EUR", "asset", null, EUR, TODAY.minusYears(1), new BigDecimal("500")))
            .accountId();
    streamingId = accountService.insertLeaf("Streaming", "expense", null, EUR).accountId();
    mockMvc
        .perform(save(null, OLD_AMOUNT, null).param("pastOccurrences", "book"))
        .andExpect(header().string("HX-Redirect", "/recurring"));
    templateId = repository.findLive().get(0).recurringTemplateId();
    assertThat(pendingDates())
        .containsExactly(
            TODAY.minusDays(15),
            TODAY.minusDays(8),
            TODAY.minusDays(1),
            TODAY.plusDays(6),
            TODAY.plusDays(13));
  }

  /** The editor's save for the weekly template; {@code end} null for no end. */
  private MockHttpServletRequestBuilder save(Long id, String amount, LocalDate end) {
    MockHttpServletRequestBuilder request =
        post("/recurring/editor/save")
            .param("name", "Cleaning")
            .param("cadenceN", "1")
            .param("cadenceUnit", "week")
            .param("endMode", end == null ? "none" : "date")
            .param("endDate", end == null ? "" : end.toString())
            .param("leadDays", "14")
            .param("confirmation", "review")
            .param("date", TODAY.minusDays(15).toString())
            .param("accountId", String.valueOf(bankId))
            .param("total", amount)
            .param("categoryText", "Streaming")
            .param("lineCategoryId", String.valueOf(streamingId))
            .param("lineCategoryType", "expense")
            .param("lineAmount", amount);
    return id == null ? request : request.param("recurringTemplateId", String.valueOf(id));
  }

  private List<LocalDate> pendingDates() {
    return jdbcClient
        .sql(
            """
            select date from transaction
            where recurring_template_id = :t and lifecycle = 'pending_review'
              and deleted_at is null
            order by date
            """)
        .param("t", templateId)
        .query(LocalDate.class)
        .list();
  }

  /** The bank leg's magnitude of the template's row on {@code date}. */
  private BigDecimal amountOn(LocalDate date) {
    return jdbcClient
        .sql(
            """
            select abs(p.amount) from posting p
            join transaction t on p.transaction_id = t.transaction_id
            where t.recurring_template_id = :t and t.date = :d and p.account_id = :a
            """)
        .param("t", templateId)
        .param("d", date)
        .param("a", bankId)
        .query(BigDecimal.class)
        .single();
  }

  private void confirm(LocalDate date) {
    jdbcClient
        .sql(
            "update transaction set lifecycle = 'confirmed'"
                + " where recurring_template_id = :t and date = :d")
        .param("t", templateId)
        .param("d", date)
        .update();
  }

  // ── a save replaces the future pending rows ─────────────────────────────────

  @Test
  void amountEditReplacesTheFuturePendingRowsAndTheRegisterShowsIt() throws Exception {
    mockMvc
        .perform(save(templateId, NEW_AMOUNT, null))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(pendingDates()).hasSize(5);
    assertThat(amountOn(TODAY.minusDays(1))).isEqualByComparingTo("9.99");
    assertThat(amountOn(TODAY.plusDays(6))).isEqualByComparingTo("12.99");
    assertThat(amountOn(TODAY.plusDays(13))).isEqualByComparingTo("12.99");
    mockMvc
        .perform(get("/register").param("accountId", String.valueOf(bankId)))
        .andExpect(content().string(containsString("12,99")));
  }

  // ── an end date that cuts pending rows off ──────────────────────────────────

  @Test
  void endDateCuttingPendingRowsOffAsksAndChangesNothingYet() throws Exception {
    mockMvc
        .perform(save(templateId, OLD_AMOUNT, TODAY.minusDays(5)))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("HX-Redirect"))
        .andExpect(content().string(containsString("The new end date cuts off 3 pending rows")))
        .andExpect(content().string(containsString("Keep only those dated before today")));

    assertThat(repository.findById(templateId).orElseThrow().endDate()).isNull();
    assertThat(pendingDates()).hasSize(5);
  }

  @Test
  void endDateAnsweredKeepPastKeepsThePastCutOffRowOnly() throws Exception {
    mockMvc
        .perform(save(templateId, OLD_AMOUNT, TODAY.minusDays(5)).param(PENDING_ROWS, "keep-past"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(pendingDates())
        .containsExactly(TODAY.minusDays(15), TODAY.minusDays(8), TODAY.minusDays(1));
  }

  @Test
  void endDateAnsweredKeepAllKeepsEveryCutOffRow() throws Exception {
    mockMvc
        .perform(save(templateId, OLD_AMOUNT, TODAY.minusDays(5)).param(PENDING_ROWS, "keep-all"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(pendingDates()).hasSize(5);
  }

  @Test
  void rowsKeptBeyondTheEndAreNotAskedAboutAgainAndStayKept() throws Exception {
    mockMvc
        .perform(save(templateId, OLD_AMOUNT, TODAY.minusDays(5)).param(PENDING_ROWS, "keep-all"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    mockMvc
        .perform(save(templateId, NEW_AMOUNT, TODAY.minusDays(5)))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(pendingDates()).hasSize(5);
  }

  @Test
  void endDateAnsweredRemoveAllRemovesEveryCutOffRow() throws Exception {
    mockMvc
        .perform(save(templateId, OLD_AMOUNT, TODAY.minusDays(5)).param(PENDING_ROWS, "remove-all"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(pendingDates()).containsExactly(TODAY.minusDays(15), TODAY.minusDays(8));
  }

  // ── delete ──────────────────────────────────────────────────────────────────

  @Test
  void deleteWithPendingRowsAsksInTheDialogSlotAndDeletesNothingYet() throws Exception {
    mockMvc
        .perform(post("/recurring/{id}/delete", templateId))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Retarget", "#recurring-dialog"))
        .andExpect(header().doesNotExist("HX-Redirect"))
        .andExpect(content().string(containsString("This template has 5 pending rows")))
        .andExpect(content().string(containsString("Remove all pending")));

    assertThat(repository.findLive()).hasSize(1);
  }

  @Test
  void deleteAsksEvenWhenThePanelCarriesTheEndDateAnswer() throws Exception {
    // The panel's Delete posts its whole form, hidden end-date answer included.
    mockMvc
        .perform(post("/recurring/{id}/delete", templateId).param(PENDING_ROWS, "remove-all"))
        .andExpect(header().string("HX-Retarget", "#recurring-dialog"));

    assertThat(pendingDates()).hasSize(5);
  }

  @Test
  void deleteKeepingAllLeavesEveryPendingRow() throws Exception {
    mockMvc
        .perform(post("/recurring/{id}/delete", templateId).param(DELETE_ANSWER, "keep-all"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(repository.findLive()).isEmpty();
    assertThat(pendingDates()).hasSize(5);
  }

  @Test
  void deleteKeepingThePastRemovesThePendingRowsFromTodayOn() throws Exception {
    mockMvc
        .perform(post("/recurring/{id}/delete", templateId).param(DELETE_ANSWER, "keep-past"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(pendingDates())
        .containsExactly(TODAY.minusDays(15), TODAY.minusDays(8), TODAY.minusDays(1));
  }

  @Test
  void deleteRemovingAllPendingKeepsTheConfirmedRows() throws Exception {
    confirm(TODAY.plusDays(6));

    mockMvc
        .perform(post("/recurring/{id}/delete", templateId).param(DELETE_ANSWER, "remove-all"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(pendingDates()).isEmpty();
    assertThat(amountOn(TODAY.plusDays(6))).isEqualByComparingTo("9.99");
  }
}
