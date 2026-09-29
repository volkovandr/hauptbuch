package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Integration tier (CLAUDE.md §6): the recurring page and the template editor (recurring sub-plan
 * slice b) driven through MockMvc against real Postgres. The editor is the split panel in template
 * mode: a template saves through the dock's own dry run, so the dock's refusals appear here, and
 * nothing the dry run wrote survives it. Saving runs the booking for that template (slice c), so a
 * start in the past asks first and then puts the occurrences in the register.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RecurringScreenIntegrationTest {

  private static final String SAVE = "/recurring/editor/save";
  private static final String EUR = "EUR";
  // Far in the future, so the next three dates do not depend on today.
  private static final String START = "2099-01-31";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired RecurringTemplateRepository repository;
  @Autowired JdbcClient jdbcClient;

  private long bankId;
  private long streamingId;
  private long householdTagId;
  private long mediaTagId;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
    bankId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankAaa-EUR",
                    "asset",
                    null,
                    EUR,
                    LocalDate.parse("2026-01-01"),
                    new BigDecimal("500")))
            .accountId();
    streamingId = accountService.insertLeaf("Streaming", "expense", null, EUR).accountId();
    householdTagId =
        jdbcClient
            .sql("insert into tag (name) values ('Household') returning tag_id")
            .query(Long.class)
            .single();
    mediaTagId =
        jdbcClient
            .sql("insert into tag (name) values ('Media') returning tag_id")
            .query(Long.class)
            .single();
  }

  /** A one-line template funded by the bank account: the simple dock entry as a split. */
  private MockHttpServletRequestBuilder saveStreaming(String name) {
    return saveStreaming(name, "month", START, "0", "auto");
  }

  private MockHttpServletRequestBuilder saveStreaming(
      String name, String cadenceUnit, String start, String leadDays, String confirmation) {
    return post(SAVE)
        .param("name", name)
        .param("cadenceN", "1")
        .param("cadenceUnit", cadenceUnit)
        .param("endMode", "none")
        .param("leadDays", leadDays)
        .param("confirmation", confirmation)
        .param("date", start)
        .param("accountId", String.valueOf(bankId))
        .param("total", "9,99")
        .param("categoryText", "Streaming")
        .param("lineCategoryId", String.valueOf(streamingId))
        .param("lineCategoryType", "expense")
        .param("lineAmount", "9,99");
  }

  private long onlyTemplateId() {
    List<RecurringTemplate> live = repository.findLive();
    assertThat(live).hasSize(1);
    return live.get(0).recurringTemplateId();
  }

  private long transactionCount() {
    return jdbcClient.sql("select count(*) from transaction").query(Long.class).single();
  }

  // ── the editor is the split panel in template mode ──────────────────────────

  @Test
  void newEditorIsTheSplitPanelInTemplateMode() throws Exception {
    mockMvc
        .perform(get("/recurring/new"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-split-panel")))
        .andExpect(content().string(containsString("New recurring template")))
        .andExpect(content().string(containsString("hx-post=\"/recurring/editor/save\"")))
        .andExpect(content().string(containsString("hx-post=\"/recurring/editor/add-line\"")))
        .andExpect(content().string(containsString(">Start</label>")))
        .andExpect(content().string(containsString("name=\"cadenceN\"")))
        // the register's filter inputs and split endpoints are not part of template mode
        .andExpect(content().string(not(containsString("viewPicker"))))
        .andExpect(content().string(not(containsString("hx-post=\"/register/split"))));
  }

  @Test
  void addLineKeepsTheScheduleBlock() throws Exception {
    mockMvc
        .perform(
            post("/recurring/editor/add-line")
                .param("name", "Streaming")
                .param("cadenceN", "2")
                .param("cadenceUnit", "week")
                .param("date", START)
                .param("accountId", String.valueOf(bankId))
                .param("total", "9,99")
                .param("categoryText", "Streaming")
                .param("lineCategoryId", String.valueOf(streamingId))
                .param("lineCategoryType", "expense")
                .param("lineAmount", "5"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"Streaming\"")))
        .andExpect(content().string(containsString("value=\"2\"")))
        .andExpect(content().string(containsString("<option value=\"week\" selected")))
        .andExpect(content().string(containsString("hx-post=\"/recurring/editor/save\"")));
  }

  @Test
  void totalTypedFirstFillsTheBlankLine() throws Exception {
    mockMvc
        .perform(
            post("/recurring/editor/currency")
                .param("name", "Streaming")
                .param("date", START)
                .param("accountId", String.valueOf(bankId))
                .param("total", "12,99")
                .param("categoryText", "")
                .param("lineAmount", ""))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    matchesPattern(
                        Pattern.compile(
                            ".*name=\"lineAmount\"[^>]*value=\"12,99\".*", Pattern.DOTALL))));
  }

  @Test
  void endChoiceShowsOnlyItsOwnInputAndKeepsTheOthersValue() throws Exception {
    mockMvc
        .perform(
            post("/recurring/editor/currency")
                .param("endMode", "after")
                .param("endDate", "2099-12-31")
                .param("endAfter", "6")
                .param("date", START)
                .param("accountId", String.valueOf(bankId)))
        .andExpect(content().string(containsString("id=\"recurring-end-after\"")))
        .andExpect(content().string(not(containsString("id=\"recurring-end-date\""))))
        // the end date rides along hidden, so switching back to it loses nothing
        .andExpect(
            content()
                .string(
                    matchesPattern(
                        Pattern.compile(
                            ".*type=\"hidden\"\\s+name=\"endDate\"\\s+value=\"2099-12-31\".*",
                            Pattern.DOTALL))));
  }

  // ── create → list ───────────────────────────────────────────────────────────

  @Test
  void createdTemplateIsListedWithItsCadenceAndNextThreeDates() throws Exception {
    long before = transactionCount();

    mockMvc
        .perform(saveStreaming("Streaming"))
        .andExpect(status().isOk())
        .andExpect(header().string("HX-Redirect", "/recurring"));

    // the dry run left nothing behind, and a start far in the future has nothing due: the save's
    // run only moves the cursor through today
    assertThat(transactionCount()).isEqualTo(before);
    assertThat(repository.findById(onlyTemplateId()).orElseThrow().bookedThrough())
        .isEqualTo(LocalDate.now());

    mockMvc
        .perform(get("/recurring"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Streaming")))
        .andExpect(content().string(containsString("every month on the 31st")))
        .andExpect(content().string(containsString("-9,99")))
        .andExpect(content().string(containsString("31.01.2099")))
        .andExpect(content().string(containsString("28.02.2099")))
        .andExpect(content().string(containsString("31.03.2099")))
        .andExpect(content().string(not(containsString("30.04.2099"))));
  }

  // ── booking on save (slice c) ───────────────────────────────────────────────

  /**
   * A weekly template that started 15 days ago: three occurrences fall before today, none on it.
   */
  private MockHttpServletRequestBuilder saveWeeklyFromThePast(String confirmation) {
    return saveStreaming(
        "Cleaning", "week", LocalDate.now().minusDays(15).toString(), "0", confirmation);
  }

  private List<Map<String, Object>> bookedRows(long templateId) {
    return jdbcClient
        .sql(
            """
            select occurrence_date, date, lifecycle from transaction
            where recurring_template_id = :t order by occurrence_date
            """)
        .param("t", templateId)
        .query()
        .listOfRows();
  }

  @Test
  void pastStartAsksWhetherToBookThePastOccurrencesAndStoresNothingYet() throws Exception {
    mockMvc
        .perform(saveWeeklyFromThePast("auto"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("HX-Redirect"))
        .andExpect(content().string(containsString("3 occurrences fall before today")))
        .andExpect(content().string(containsString("Book the 3 past occurrences")))
        .andExpect(content().string(containsString("Start from the next one")));

    assertThat(repository.findLive()).isEmpty();
  }

  @Test
  void bookingThePastPutsEachOccurrenceInTheRegisterWithTheRecurringMarker() throws Exception {
    LocalDate start = LocalDate.now().minusDays(15);
    mockMvc
        .perform(saveWeeklyFromThePast("auto").param("pastOccurrences", "book"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    long templateId = onlyTemplateId();
    List<Map<String, Object>> booked = bookedRows(templateId);
    assertThat(booked)
        .extracting(row -> row.get("occurrence_date"))
        .containsExactly(
            java.sql.Date.valueOf(start),
            java.sql.Date.valueOf(start.plusWeeks(1)),
            java.sql.Date.valueOf(start.plusWeeks(2)));
    // dated on the occurrence date, confirmed for an automatic template
    assertThat(booked)
        .allSatisfy(row -> assertThat(row.get("date")).isEqualTo(row.get("occurrence_date")));
    assertThat(booked).allSatisfy(row -> assertThat(row.get("lifecycle")).isEqualTo("confirmed"));
    assertThat(repository.findById(templateId).orElseThrow().bookedThrough())
        .isEqualTo(LocalDate.now());

    mockMvc
        .perform(get("/register").param("accountId", String.valueOf(bankId)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("register__recurring")))
        .andExpect(content().string(containsString("href=\"/recurring/" + templateId + "\"")));
  }

  @Test
  void deletedTemplateKeepsItsMarkerGreyedWithoutLink() throws Exception {
    mockMvc
        .perform(saveWeeklyFromThePast("auto").param("pastOccurrences", "book"))
        .andExpect(header().string("HX-Redirect", "/recurring"));
    long templateId = onlyTemplateId();

    mockMvc.perform(post("/recurring/" + templateId + "/delete")).andExpect(status().isOk());

    mockMvc
        .perform(get("/register").param("accountId", String.valueOf(bankId)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("register__recurring--deleted")))
        .andExpect(content().string(not(containsString("href=\"/recurring/" + templateId + "\""))));
  }

  @Test
  void startingFromTheNextBooksNothingPast() throws Exception {
    mockMvc
        .perform(saveWeeklyFromThePast("auto").param("pastOccurrences", "skip"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    long templateId = onlyTemplateId();
    assertThat(bookedRows(templateId)).isEmpty();
    assertThat(repository.findById(templateId).orElseThrow().bookedThrough())
        .isEqualTo(LocalDate.now());
  }

  @Test
  void reviewTemplateBooksPendingRows() throws Exception {
    mockMvc
        .perform(saveWeeklyFromThePast("review").param("pastOccurrences", "book"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(bookedRows(onlyTemplateId()))
        .hasSize(3)
        .allSatisfy(row -> assertThat(row.get("lifecycle")).isEqualTo("pending_review"));
  }

  @Test
  void occurrenceInsideTheLeadTimeBooksWithoutAsking() throws Exception {
    LocalDate inThreeDays = LocalDate.now().plusDays(3);
    mockMvc
        .perform(saveStreaming("Rent", "month", inThreeDays.toString(), "5", "auto"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    assertThat(bookedRows(onlyTemplateId()))
        .extracting(row -> row.get("occurrence_date"))
        .containsExactly(java.sql.Date.valueOf(inThreeDays));
  }

  // ── edit round-trips ────────────────────────────────────────────────────────

  @Test
  void multiLineTemplateReopensWithEveryField() throws Exception {
    mockMvc
        .perform(
            post(SAVE)
                .param("name", "Streaming family")
                .param("cadenceN", "2")
                .param("cadenceUnit", "month")
                .param("endMode", "date")
                .param("endDate", "2099-12-31")
                .param("leadDays", "3")
                .param("confirmation", "review")
                .param("managementUrl", "https://example.org/plan")
                .param("date", START)
                .param("accountId", String.valueOf(bankId))
                .param("payeeText", "ShopAaa")
                .param("note", "family plan")
                .param("tagId", String.valueOf(householdTagId))
                .param("total", "20")
                .param("categoryText", "Streaming", "for Doe")
                .param("lineCategoryId", String.valueOf(streamingId), "")
                .param("lineCategoryType", "expense", "")
                .param("lineTransferDirection", "", "")
                .param("linePersonName", "", "Doe")
                .param("linePersonDirection", "", "FOR")
                .param("linePersonRevive", "", "")
                .param("lineAmount", "12,50", "7,50")
                .param("lineNote", "mine", "")
                .param("lineTag0", String.valueOf(mediaTagId)))
        .andExpect(header().string("HX-Redirect", "/recurring"));
    long id = onlyTemplateId();

    mockMvc
        .perform(get("/recurring/{id}", id))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Edit recurring template")))
        .andExpect(content().string(containsString("value=\"Streaming family\"")))
        .andExpect(content().string(containsString("<option value=\"month\" selected")))
        .andExpect(content().string(containsString("value=\"2099-12-31\"")))
        .andExpect(content().string(containsString("value=\"3\"")))
        .andExpect(content().string(containsString("<option value=\"review\" selected")))
        .andExpect(content().string(containsString("value=\"https://example.org/plan\"")))
        .andExpect(content().string(containsString("value=\"" + START + "\"")))
        .andExpect(content().string(containsString("ShopAaa")))
        .andExpect(content().string(containsString("value=\"family plan\"")))
        .andExpect(content().string(containsString("Household")))
        .andExpect(content().string(containsString("Media")))
        .andExpect(content().string(containsString("value=\"Streaming\"")))
        .andExpect(content().string(containsString("value=\"12,50\"")))
        .andExpect(content().string(containsString("value=\"mine\"")))
        .andExpect(content().string(containsString("value=\"for Doe\"")))
        .andExpect(content().string(containsString("value=\"7,50\"")));

    // Saving the reopened template back changes only what was edited.
    mockMvc
        .perform(saveStreaming("Streaming solo").param("recurringTemplateId", String.valueOf(id)))
        .andExpect(header().string("HX-Redirect", "/recurring"));
    RecurringTemplate edited = repository.findById(id).orElseThrow();
    assertThat(edited.name()).isEqualTo("Streaming solo");
    assertThat(edited.endDate()).isNull();
    assertThat(repository.findLines(id)).hasSize(1);
    assertThat(repository.findTagIds(id)).isEmpty();
  }

  @Test
  void multiLineTemplateStoresEachLinesOwnTags() throws Exception {
    mockMvc.perform(
        saveStreaming("Tagged")
            .param("tagId", String.valueOf(householdTagId))
            .param("lineTag0", String.valueOf(mediaTagId)));
    long id = onlyTemplateId();

    assertThat(repository.findTagIds(id)).containsExactly(householdTagId);
    RecurringTemplateLine line = repository.findLines(id).get(0);
    assertThat(repository.findLineTagIds(line.recurringTemplateLineId()))
        .containsExactly(mediaTagId);
  }

  @Test
  void personFundedTemplateReopensWithTheSigil() throws Exception {
    mockMvc
        .perform(
            post(SAVE)
                .param("name", "Pocket money")
                .param("cadenceN", "1")
                .param("cadenceUnit", "week")
                .param("endMode", "none")
                .param("leadDays", "0")
                .param("confirmation", "auto")
                .param("date", START)
                .param("fundingPersonName", "Doe")
                .param("fundingPersonDirection", "BY")
                .param("total", "50")
                .param("categoryText", "Streaming")
                .param("lineCategoryId", String.valueOf(streamingId))
                .param("lineCategoryType", "expense")
                .param("lineAmount", "50"))
        .andExpect(header().string("HX-Redirect", "/recurring"));
    long id = onlyTemplateId();

    RecurringTemplate stored = repository.findById(id).orElseThrow();
    assertThat(stored.accountId()).isNull();
    assertThat(stored.personId()).isNotNull();
    // No currency was picked: the one the dock would book in (Doe has no debts yet, so base) is
    // fixed now, so a later second debt currency cannot move it.
    assertThat(stored.spendingCurrencyCode()).isEqualTo(EUR);
    mockMvc
        .perform(get("/recurring/{id}", id))
        .andExpect(content().string(containsString("value=\"by Doe\"")));
    mockMvc.perform(get("/recurring")).andExpect(content().string(containsString("by Doe")));

    // Saved back as reopened (the sigil resolves to the same person), with a new amount.
    mockMvc
        .perform(
            post(SAVE)
                .param("recurringTemplateId", String.valueOf(id))
                .param("name", "Pocket money")
                .param("cadenceN", "1")
                .param("cadenceUnit", "week")
                .param("endMode", "none")
                .param("date", START)
                .param("fundingPersonName", "Doe")
                .param("fundingPersonDirection", "BY")
                .param("spendingCurrencyCode", EUR)
                .param("total", "60")
                .param("categoryText", "Streaming")
                .param("lineCategoryId", String.valueOf(streamingId))
                .param("lineCategoryType", "expense")
                .param("lineAmount", "60"))
        .andExpect(header().string("HX-Redirect", "/recurring"));
    RecurringTemplate resaved = repository.findById(id).orElseThrow();
    assertThat(resaved.personId()).isEqualTo(stored.personId());
    assertThat(repository.findLines(id).get(0).amount()).isEqualByComparingTo("60");
  }

  @Test
  void personFundedTransferIntoAnotherCurrencyIsRefused() throws Exception {
    long usdAccount =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankBbb-USD",
                    "asset",
                    null,
                    "USD",
                    LocalDate.parse("2026-01-01"),
                    new BigDecimal("100")))
            .accountId();

    mockMvc
        .perform(
            post(SAVE)
                .param("name", "Doe pays in")
                .param("cadenceN", "1")
                .param("cadenceUnit", "month")
                .param("endMode", "none")
                .param("date", START)
                .param("fundingPersonName", "Doe")
                .param("fundingPersonDirection", "BY")
                .param("total", "50")
                .param("categoryText", "From ← BankBbb-USD")
                .param("lineCategoryId", String.valueOf(usdAccount))
                .param("lineTransferDirection", "TO")
                .param("lineAmount", "50"))
        .andExpect(header().doesNotExist("HX-Redirect"))
        .andExpect(content().string(containsString("A transfer line must target a EUR account")));

    assertThat(repository.findLive()).isEmpty();
  }

  // ── delete ──────────────────────────────────────────────────────────────────

  @Test
  void deletedTemplateLeavesTheList() throws Exception {
    mockMvc.perform(saveStreaming("Anime club"));
    long id = onlyTemplateId();

    mockMvc
        .perform(post("/recurring/{id}/delete", id))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    mockMvc
        .perform(get("/recurring"))
        .andExpect(content().string(not(containsString("Anime club"))))
        .andExpect(content().string(containsString("No recurring templates yet.")));
  }

  // ── refusals ────────────────────────────────────────────────────────────────

  @Test
  void theDocksRefusalAppearsInTemplateModeAndNothingIsStored() throws Exception {
    mockMvc
        .perform(
            post(SAVE)
                .param("name", "Self transfer")
                .param("cadenceN", "1")
                .param("cadenceUnit", "month")
                .param("endMode", "none")
                .param("date", START)
                .param("accountId", String.valueOf(bankId))
                .param("total", "100")
                .param("categoryText", "To → BankAaa-EUR")
                .param("lineCategoryId", String.valueOf(bankId))
                .param("lineTransferDirection", "TO")
                .param("lineAmount", "100"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("HX-Redirect"))
        .andExpect(content().string(containsString("A transfer needs two different accounts")))
        // the panel redisplays in template mode, keeping what was typed
        .andExpect(content().string(containsString("value=\"Self transfer\"")))
        .andExpect(content().string(containsString("hx-post=\"/recurring/editor/save\"")));

    assertThat(repository.findLive()).isEmpty();
  }

  @Test
  void scheduleRefusalAppearsInThePanel() throws Exception {
    mockMvc
        .perform(saveStreaming(""))
        .andExpect(header().doesNotExist("HX-Redirect"))
        .andExpect(content().string(containsString("A template needs a name")));

    assertThat(repository.findLive()).isEmpty();
  }
}
