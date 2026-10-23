package volkovandr.hauptbuch.statements;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The statement page's previous / next links (statements issue 17): each statement links to its
 * neighbours on the same account, and only where a neighbour exists. The neighbour choice itself is
 * covered in the SQL-logic tier.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementNeighbourLinksIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  private long accountId;
  private long profileId;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency("EUR");
    accountId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BankAaa-EUR",
                    "asset",
                    null,
                    "EUR",
                    LocalDate.parse("2026-01-01"),
                    BigDecimal.ZERO))
            .accountId();
    profileId =
        jdbcClient
            .sql(
                "insert into statement_profile (name, format) values ('BankAaa CSV', 'csv')"
                    + " returning statement_profile_id")
            .query(Long.class)
            .single();
  }

  private long statement(String start, String end) {
    return jdbcClient
        .sql(
            """
            insert into statement
              (statement_profile_id, account_id, state, original_filename, file_path,
               period_start, period_end)
            values (:p, :a, 'processed', 'x.csv', 'x', :s, :e)
            returning statement_id
            """)
        .param("p", profileId)
        .param("a", accountId)
        .param("s", LocalDate.parse(start))
        .param("e", LocalDate.parse(end))
        .query(Long.class)
        .single();
  }

  @Test
  void theStatementPageLinksToTheNeighbouringStatementsOfItsAccount() throws Exception {
    long may = statement("2026-05-01", "2026-05-31");
    long june = statement("2026-06-01", "2026-06-30");

    mockMvc
        .perform(get("/statements/" + may))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/statements/" + june + "\"")))
        .andExpect(content().string(containsString("data-statement-next")))
        .andExpect(content().string(not(containsString("data-statement-prev"))));
    mockMvc
        .perform(get("/statements/" + june))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"/statements/" + may + "\"")))
        .andExpect(content().string(containsString("data-statement-prev")))
        .andExpect(content().string(not(containsString("data-statement-next"))));
  }
}
