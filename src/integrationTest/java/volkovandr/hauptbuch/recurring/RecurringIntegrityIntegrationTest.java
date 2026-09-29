package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Integration tier (CLAUDE.md §6): booking failures and referential integrity (data-model §14.3,
 * recurring sub-plan slice f), through MockMvc against real Postgres. A template that cannot book
 * is still saved, names its reason on the main page and the recurring page, and clears on the first
 * run that completes. A person merge and a subdivision carry the templates' references along with
 * the postings, and a category a live template uses cannot be deleted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RecurringIntegrityIntegrationTest {

  private static final String EUR = "EUR";
  private static final String CLOSED = "Account &#39;BankAaa-EUR&#39; is closed";
  private static final String WARNINGS = "/overview/recurring-warnings";
  private static final LocalDate TODAY = LocalDate.now();

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired PersonService personService;
  @Autowired SettingsService settingsService;
  @Autowired RecurringTemplateRepository repository;
  @Autowired RecurringBookingScheduler scheduler;
  @Autowired JdbcClient jdbcClient;

  private long bankId;
  private long streamingId;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
    bankId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankAaa-EUR", "asset", null, EUR, TODAY.minusYears(1), new BigDecimal("500")))
            .accountId();
    streamingId = accountService.insertLeaf("Streaming", "expense", null, EUR).accountId();
  }

  /** A weekly template that began 15 days ago, booking its three past occurrences on save. */
  private MockHttpServletRequestBuilder saveWeekly() {
    return post("/recurring/editor/save")
        .param("name", "Cleaning")
        .param("cadenceN", "1")
        .param("cadenceUnit", "week")
        .param("endMode", "none")
        .param("leadDays", "0")
        .param("confirmation", "auto")
        .param("pastOccurrences", "book")
        .param("date", TODAY.minusDays(15).toString())
        .param("accountId", String.valueOf(bankId))
        .param("total", "9,99")
        .param("categoryText", "Streaming")
        .param("lineCategoryId", String.valueOf(streamingId))
        .param("lineCategoryType", "expense")
        .param("lineAmount", "9,99");
  }

  private long templateId() {
    return repository.findLive().get(0).recurringTemplateId();
  }

  /**
   * A weekly template stored directly, begun 15 days ago with nothing booked yet: a Streaming line,
   * or {@code lines} when given.
   */
  private long insertWeekly(String name, long accountId, List<RecurringTemplateLineDraft> lines) {
    return repository.insert(
        new RecurringTemplateDraft(
            name,
            TODAY.minusDays(15),
            "week",
            1,
            null,
            0,
            "auto",
            false,
            null,
            null,
            accountId,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            lines.isEmpty()
                ? List.of(
                    new RecurringTemplateLineDraft(
                        streamingId, null, null, null, new BigDecimal("9.99"), null, List.of()))
                : lines),
        TODAY.minusDays(16));
  }

  private int bookedRows(long templateId) {
    return jdbcClient
        .sql("select count(*) from transaction where recurring_template_id = :t")
        .param("t", templateId)
        .query(Integer.class)
        .single();
  }

  // ── booking failures ────────────────────────────────────────────────────────

  @Test
  void templateThatCannotBookIsSavedAndWarnsUntilRunCompletes() throws Exception {
    accountService.closeAccount(bankId, TODAY.minusDays(1));

    mockMvc.perform(saveWeekly()).andExpect(header().string("HX-Redirect", "/recurring"));

    long id = templateId();
    assertThat(bookedRows(id)).isZero();
    assertThat(repository.findById(id).orElseThrow().bookedThrough())
        .isEqualTo(TODAY.minusDays(16));
    mockMvc
        .perform(get(WARNINGS))
        .andExpect(content().string(containsString("Could not book")))
        .andExpect(content().string(containsString("/recurring/" + id)))
        .andExpect(content().string(containsString(CLOSED)));
    mockMvc.perform(get("/recurring")).andExpect(content().string(containsString(CLOSED)));
    mockMvc
        .perform(get("/").param("desktop", ""))
        .andExpect(content().string(containsString(WARNINGS)));

    accountService.reopenAccount(bankId);
    scheduler.bookDueOccurrences("scheduled");

    assertThat(bookedRows(id)).isEqualTo(3);
    mockMvc
        .perform(get(WARNINGS))
        .andExpect(content().string(not(containsString("Could not book"))));
    mockMvc.perform(get("/recurring")).andExpect(content().string(not(containsString(CLOSED))));
  }

  @Test
  void failingTemplateDoesNotBlockTheNextOne() {
    long savingsId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankBbb-EUR", "asset", null, EUR, TODAY.minusYears(1), BigDecimal.ZERO))
            .accountId();
    final long failing = insertWeekly("Anime", savingsId, List.of());
    long healthy = insertWeekly("Cleaning", bankId, List.of());
    accountService.closeAccount(savingsId, TODAY.minusDays(1));

    scheduler.bookDueOccurrences("scheduled");

    assertThat(bookedRows(healthy)).isEqualTo(3);
    assertThat(bookedRows(failing)).isZero();
    assertThat(repository.findById(failing).orElseThrow().bookedThrough())
        .isEqualTo(TODAY.minusDays(16));
    assertThat(repository.findLiveBookingFailures())
        .extracting(RecurringBookingFailure::recurringTemplateId)
        .containsExactly(failing);
  }

  // ── merges, subdivision and deletion ────────────────────────────────────────

  @Test
  void personMergeRewritesTheTemplatesPersonReferences() throws Exception {
    long max = personService.create("Max").personId();
    long doe = personService.create("Doe").personId();
    long id =
        insertWeekly(
            "Pocket money",
            bankId,
            List.of(
                new RecurringTemplateLineDraft(
                    null, null, max, "FOR", new BigDecimal("50"), null, List.of())));

    mockMvc
        .perform(post("/people/" + max + "/merge").param("targetPersonId", String.valueOf(doe)))
        .andExpect(status().is3xxRedirection());

    assertThat(repository.findLines(id))
        .extracting(RecurringTemplateLine::personId)
        .containsExactly(doe);
  }

  @Test
  void subdivisionMovesTheTemplateOntoTheCatchAll() throws Exception {
    mockMvc.perform(saveWeekly()).andExpect(status().isOk());
    long id = templateId();

    mockMvc
        .perform(
            post("/categories")
                .param("name", "Video")
                .param("type", "expense")
                .param("parentId", String.valueOf(streamingId)))
        .andExpect(status().is3xxRedirection());

    long lineAccountId = repository.findLines(id).get(0).accountId();
    assertThat(accountService.findById(lineAccountId).orElseThrow().name())
        .isEqualTo("Uncategorized");
  }

  @Test
  void categoryLiveTemplateUsesCannotBeDeleted() throws Exception {
    mockMvc.perform(saveWeekly()).andExpect(status().isOk());
    long otherId = accountService.insertLeaf("Books", "expense", null, EUR).accountId();

    mockMvc
        .perform(get("/categories/" + streamingId))
        .andExpect(content().string(containsString("In use by")))
        .andExpect(content().string(containsString("recurring template &#39;Cleaning&#39;")))
        .andExpect(content().string(not(containsString("Delete category</button>"))));
    assertThatThrownBy(
            () ->
                mockMvc.perform(
                    post("/categories/" + streamingId + "/delete")
                        .param("targetLeafId", String.valueOf(otherId))))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recurring template 'Cleaning'");
    assertThat(accountService.findById(streamingId).orElseThrow().deletedAt()).isNull();
  }

  @Test
  void categoryOnlyDeletedTemplateUsedCanBeDeleted() throws Exception {
    repository.softDelete(insertWeekly("Cleaning", bankId, List.of()));

    mockMvc
        .perform(post("/categories/" + streamingId + "/delete"))
        .andExpect(status().is3xxRedirection());

    assertThat(accountService.findById(streamingId).orElseThrow().deletedAt()).isNotNull();
  }
}
