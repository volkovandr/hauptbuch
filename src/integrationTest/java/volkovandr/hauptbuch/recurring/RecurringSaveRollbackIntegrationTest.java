package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.DockSplitService;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Integration tier (CLAUDE.md §6): a save whose booking fails partway rolls back only the run
 * (data-model §14.3, recurring sub-plan slice f). The run sits behind a savepoint, so the
 * occurrence it already booked and the cursor it would have moved roll back, while the template
 * itself is saved and carries the failure. The dock is spied on to refuse the second occurrence
 * after the first has been written, the case a pre-check cannot reach. It has a class of its own
 * because the spy changes the application context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RecurringSaveRollbackIntegrationTest {

  private static final String EUR = "EUR";
  private static final LocalDate TODAY = LocalDate.now();

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired RecurringTemplateRepository repository;
  @Autowired JdbcClient jdbcClient;
  @MockitoSpyBean DockSplitService dockSplitService;

  @Test
  void failureMidRunRollsBackTheRunButKeepsTheTemplate() throws Exception {
    settingsService.setBaseCurrency(EUR);
    long bankId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankAaa-EUR", "asset", null, EUR, TODAY.minusYears(1), new BigDecimal("500")))
            .accountId();
    long streamingId = accountService.insertLeaf("Streaming", "expense", null, EUR).accountId();
    LocalDate second = TODAY.minusDays(8);
    doThrow(new IllegalStateException("No rate for CHF"))
        .when(dockSplitService)
        .commit(argThat(entry -> second.equals(entry.date())));

    mockMvc
        .perform(
            post("/recurring/editor/save")
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
                .param("lineAmount", "9,99"))
        .andExpect(header().string("HX-Redirect", "/recurring"));

    RecurringTemplate template = repository.findLive().get(0);
    assertThat(template.bookedThrough()).isEqualTo(TODAY.minusDays(16));
    assertThat(
            jdbcClient
                .sql("select count(*) from transaction where recurring_template_id = :t")
                .param("t", template.recurringTemplateId())
                .query(Integer.class)
                .single())
        .isZero();
    assertThat(repository.findLiveBookingFailures())
        .extracting(RecurringBookingFailure::reason)
        .containsExactly("No rate for CHF");
  }
}
