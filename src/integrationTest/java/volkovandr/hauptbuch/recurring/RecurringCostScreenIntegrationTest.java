package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

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
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Integration tier (CLAUDE.md §6): the recurring page's figures, its Recurring cost, Transfers and
 * People tables, and the end reminder (data-model §14.4, recurring sub-plan slice g), through
 * MockMvc against real Postgres. The unit tests own the arithmetic; this proves the screens render
 * it, a mixed-currency set included, and that Dismiss unticks the reminder and removes the
 * main-page line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RecurringCostScreenIntegrationTest {

  private static final String EUR = "EUR";
  private static final LocalDate TODAY = LocalDate.now();

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired RecurringTemplateRepository repository;
  @Autowired JdbcClient jdbcClient;
  @Autowired PersonService personService;

  private long bankId;
  private long chfBankId;
  private long mediaId;
  private long salaryId;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
    bankId = open("BankAaa-EUR", EUR);
    chfBankId = open("BankBbb-CHF", "CHF");
    mediaId = accountService.insertLeaf("Media", "expense", null, EUR).accountId();
    salaryId = accountService.insertLeaf("Salary", "income", null, EUR).accountId();
    jdbcClient
        .sql(
            "insert into exchange_rate (currency_code, date, rate, source)"
                + " values ('CHF', :d, 1.1, 'manual')")
        .param("d", TODAY.minusDays(3))
        .update();
  }

  private long open(String name, String currency) {
    return accountService
        .openAccount(
            new AccountDraft(name, "asset", null, currency, TODAY.minusYears(1), BigDecimal.ZERO))
        .accountId();
  }

  /** A monthly template starting tomorrow, stored directly: nothing is booked. */
  private long insertMonthly(
      String name, long accountId, long categoryId, String amount, LocalDate end, boolean remind) {
    return repository.insert(
        new RecurringTemplateDraft(
            name,
            TODAY.plusDays(1),
            "month",
            1,
            end,
            0,
            "auto",
            remind,
            remind ? 30 : null,
            null,
            accountId,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(
                new RecurringTemplateLineDraft(
                    categoryId, null, null, null, new BigDecimal(amount), null, List.of()))),
        TODAY);
  }

  @Test
  void pageShowsEachTemplatesCostAndTheSummaryInBase() throws Exception {
    insertMonthly("Streaming", chfBankId, mediaId, "10", null, false);
    insertMonthly("Gym", bankId, mediaId, "30", TODAY.plusMonths(12), false);
    insertMonthly("Salary", bankId, salaryId, "3000", null, false);

    mockMvc
        .perform(get("/recurring"))
        // Streaming in CHF with base at the latest rate; Gym ends, so it also has a total.
        .andExpect(content().string(containsString("(-11,00)")))
        .andExpect(content().string(containsString("-360,00")))
        .andExpect(content().string(containsString("yet to pay")))
        .andExpect(content().string(containsString("Recurring cost")))
        // Media: 11,00 + 30,00 a month; income 3.000,00; net 2.959,00.
        .andExpect(content().string(containsString("41,00")))
        .andExpect(content().string(containsString("--depth: 1")))
        .andExpect(content().string(containsString("3.000,00")))
        .andExpect(content().string(containsString("2.959,00")))
        .andExpect(content().string(containsString("Schedule math, not bookkeeping")))
        // No transfer and no person: neither table shows.
        .andExpect(content().string(not(containsString("between your own accounts"))))
        .andExpect(content().string(not(containsString("the debt of each person"))));
  }

  @Test
  void transfersAndPeopleShowInTablesOfTheirOwn() throws Exception {
    long cashId = open("Cash", EUR);
    long maxId = personService.create("Max").personId();
    insertTemplate(
        "Withdrawal",
        bankId,
        null,
        new RecurringTemplateLineDraft(
            cashId, "TO", null, null, new BigDecimal("200"), null, List.of()));
    insertTemplate(
        "Pocket money",
        null,
        maxId,
        new RecurringTemplateLineDraft(
            mediaId, null, null, null, new BigDecimal("20"), null, List.of()));

    mockMvc
        .perform(get("/recurring"))
        .andExpect(content().string(containsString("between your own accounts")))
        .andExpect(content().string(containsString("→ Cash")))
        .andExpect(content().string(containsString("200,00")))
        .andExpect(content().string(containsString("the debt of each person")))
        .andExpect(content().string(containsString("Max (EUR)")))
        .andExpect(content().string(containsString("you owe Max more")))
        .andExpect(content().string(containsString("-240,00")));
  }

  /** A monthly template with one line, funded by an account or by a person (BY). */
  private void insertTemplate(
      String name, Long accountId, Long personId, RecurringTemplateLineDraft line) {
    repository.insert(
        new RecurringTemplateDraft(
            name,
            TODAY.plusDays(1),
            "month",
            1,
            null,
            0,
            "auto",
            false,
            null,
            null,
            accountId,
            personId,
            personId == null ? null : "BY",
            null,
            null,
            null,
            List.of(),
            List.of(line)),
        TODAY);
  }

  @Test
  void endReminderWarnsFromItsFirstDayAndDismissUnticksIt() throws Exception {
    long ending = insertMonthly("Gym", bankId, mediaId, "30", TODAY.plusDays(20), true);
    insertMonthly("Club", bankId, mediaId, "5", TODAY.plusDays(60), true);

    mockMvc
        .perform(get("/overview/recurring-warnings"))
        .andExpect(content().string(containsString("/recurring/" + ending)))
        .andExpect(content().string(containsString("ends on")))
        .andExpect(content().string(not(containsString("Club"))));

    mockMvc
        .perform(post("/recurring/" + ending + "/dismiss-reminder"))
        .andExpect(content().string(not(containsString("ends on"))));

    assertThat(repository.findById(ending).orElseThrow().endReminder()).isFalse();
  }

  @Test
  void editorSavesAndReopensTheEndReminder() throws Exception {
    mockMvc
        .perform(
            post("/recurring/editor/save")
                .param("name", "Gym")
                .param("cadenceN", "1")
                .param("cadenceUnit", "month")
                .param("endMode", "date")
                .param("endDate", TODAY.plusMonths(6).toString())
                .param("endReminder", "true")
                .param("endReminderDays", "14")
                .param("leadDays", "0")
                .param("confirmation", "auto")
                .param("date", TODAY.plusDays(1).toString())
                .param("accountId", String.valueOf(bankId))
                .param("total", "30")
                .param("categoryText", "Media")
                .param("lineCategoryId", String.valueOf(mediaId))
                .param("lineCategoryType", "expense")
                .param("lineAmount", "30"))
        .andExpect(header().string("HX-Redirect", "/recurring"));
    RecurringTemplate saved = repository.findLive().get(0);
    assertThat(saved.endReminder()).isTrue();
    assertThat(saved.endReminderDays()).isEqualTo(14);

    mockMvc
        .perform(get("/recurring/" + saved.recurringTemplateId()))
        .andExpect(content().string(containsString("name=\"endReminder\"")))
        .andExpect(content().string(containsString("checked")))
        .andExpect(content().string(containsString("value=\"14\"")));
  }
}
