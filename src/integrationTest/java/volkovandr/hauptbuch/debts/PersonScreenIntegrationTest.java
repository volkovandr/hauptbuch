package volkovandr.hauptbuch.debts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): the stage-8a people screen driven through the controller against
 * real Postgres — the stage's acceptance surface. Names only: balances, per-currency breakdown, and
 * entry arrive in later 8-series sub-stages.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container — including the
 * base-currency write, which is write-once and would otherwise lock the shared book.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PersonScreenIntegrationTest {

  private static final String PEOPLE_PATH = "/people";
  private static final String PERSON_PATH_PREFIX = "/people/";
  private static final String NAME = "name";
  private static final String EUR = "EUR";
  private static final String MAX = "Max";
  private static final String MAXIMILIAN = "Maximilian";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long personIdNamed(String name) {
    return jdbcClient
        .sql("select person_id from person where name = :name and deleted_at is null")
        .param(NAME, name)
        .query(Long.class)
        .single();
  }

  @Test
  void screenOffersTheCreateFormWhenEmpty() throws Exception {
    mockMvc
        .perform(get(PEOPLE_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Add a person")))
        .andExpect(content().string(containsString("No people yet.")));
  }

  @Test
  void createdPersonAppearsInTheList() throws Exception {
    mockMvc
        .perform(post(PEOPLE_PATH).param(NAME, MAX))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(PEOPLE_PATH));

    mockMvc.perform(get(PEOPLE_PATH)).andExpect(content().string(containsString(MAX)));
  }

  @Test
  void editRenamesPerson() throws Exception {
    mockMvc.perform(post(PEOPLE_PATH).param(NAME, MAX)).andExpect(status().is3xxRedirection());
    long personId = personIdNamed(MAX);

    mockMvc
        .perform(get(PERSON_PATH_PREFIX + personId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Remove this person")));

    mockMvc
        .perform(post(PERSON_PATH_PREFIX + personId).param(NAME, MAXIMILIAN))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(PEOPLE_PATH));

    mockMvc.perform(get(PEOPLE_PATH)).andExpect(content().string(containsString(MAXIMILIAN)));
  }

  @Test
  void zeroBalancePersonIsSoftDeletedAndDisappearsFromTheList() throws Exception {
    mockMvc.perform(post(PEOPLE_PATH).param(NAME, MAX)).andExpect(status().is3xxRedirection());
    long personId = personIdNamed(MAX);

    mockMvc
        .perform(post(PERSON_PATH_PREFIX + personId + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(PEOPLE_PATH));

    mockMvc.perform(get(PEOPLE_PATH)).andExpect(content().string(not(containsString(MAX))));

    List<String> deletedAt =
        jdbcClient
            .sql("select deleted_at from person where person_id = :id and deleted_at is not null")
            .param("id", personId)
            .query(String.class)
            .list();
    assertThat(deletedAt).hasSize(1);
  }

  @Test
  void nonZeroBalancePersonRefusesSoftDelete() throws Exception {
    mockMvc.perform(post(PEOPLE_PATH).param(NAME, MAX)).andExpect(status().is3xxRedirection());
    long personId = personIdNamed(MAX);

    // Provision the person's EUR leaf and give it a non-zero balance directly, bypassing entry
    // (stage 8b lands the register path) — this stage only needs the guard proven.
    long accountId =
        jdbcClient
            .sql(
                "insert into account (name, type, currency_code) values ('personal.EUR', 'asset',"
                    + " 'EUR') returning account_id")
            .query(Long.class)
            .single();
    jdbcClient
        .sql("insert into account_owner (account_id, person_id) values (:a, :p)")
        .param("a", accountId)
        .param("p", personId)
        .update();
    long transactionId =
        jdbcClient
            .sql("insert into transaction (date) values (current_date) returning transaction_id")
            .query(Long.class)
            .single();
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", transactionId)
        .param("a", accountId)
        .param("amt", new BigDecimal("25.00"))
        .update();

    assertThatThrownBy(() -> mockMvc.perform(post(PERSON_PATH_PREFIX + personId + "/delete")))
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-zero balance");

    Optional<String> deletedAt =
        jdbcClient
            .sql("select deleted_at from person where person_id = :id")
            .param("id", personId)
            .query(String.class)
            .optional();
    assertThat(deletedAt).isEmpty();
  }
}
