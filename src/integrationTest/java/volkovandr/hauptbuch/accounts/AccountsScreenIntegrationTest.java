package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Integration tier (plan §1.5): the stage-6a accounts screen driven through the controller against
 * real Postgres — the stage's acceptance surface. An account can be opened in any seeded currency,
 * and an opening balance entered German-style becomes a <em>real balanced transaction</em> against
 * the per-currency Opening Balances leaf (data-model T-DM-4), booked through the engine.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container — including the
 * base-currency write, which is write-once and would otherwise lock the shared book.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountsScreenIntegrationTest {

  private static final String ACCOUNTS_PATH = "/accounts";
  private static final String ACCOUNT_PATH_PREFIX = "/accounts/";
  private static final String NAME = "name";
  private static final String TYPE = "type";
  private static final String ASSET = "asset";
  private static final String CURRENCY_CODE = "currencyCode";
  private static final String OPENED_AT = "openedAt";
  private static final String OPENED_DAY = "2026-07-01";
  private static final String EUR = "EUR";
  private static final String GIRO = "Giro";
  private static final String ALTES_KONTO = "Altes Konto";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  @BeforeEach
  void setUp() {
    // Stage-5 prerequisite: the base currency is locked before accounts are managed (plan stage 6).
    settingsService.setBaseCurrency(EUR);
  }

  private long accountIdNamed(String name) {
    return jdbcClient
        .sql("select account_id from account where name = :name")
        .param(NAME, name)
        .query(Long.class)
        .single();
  }

  @Test
  void screenOffersTheSeededCurrenciesAndTheOpenForm() throws Exception {
    mockMvc
        .perform(get(ACCOUNTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Open an account")))
        .andExpect(content().string(containsString("EUR — Euro")))
        .andExpect(content().string(containsString("JPY — Japanese Yen")));
  }

  @Test
  void currencyPickerAddButtonCarriesRenderedHxGet() throws Exception {
    // Guards the "nothing happens on click" bug: this project has no htmx Thymeleaf dialect, so the
    // fragment must emit hx-* via th:attr. Assert the rendered attribute is actually present.
    mockMvc
        .perform(get(ACCOUNTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Add currency…")))
        .andExpect(
            content().string(containsString("hx-get=\"/currencies/new?fieldId=currencyCode")))
        .andExpect(content().string(containsString("hx-target=\"#currency-dialog-currencyCode\"")));
  }

  @Test
  void openedAccountAppearsInTheListWithStoredHue() throws Exception {
    mockMvc
        .perform(
            post(ACCOUNTS_PATH)
                .param(NAME, GIRO)
                .param(TYPE, ASSET)
                .param(CURRENCY_CODE, EUR)
                .param(OPENED_AT, OPENED_DAY))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(ACCOUNTS_PATH));

    mockMvc.perform(get(ACCOUNTS_PATH)).andExpect(content().string(containsString(GIRO)));

    Integer hue =
        jdbcClient
            .sql("select hue from account where account_id = :id")
            .param("id", accountIdNamed(GIRO))
            .query(Integer.class)
            .single();
    assertThat(hue).isBetween(0, 359);
  }

  @Test
  void openingBalanceBecomesRealBalancedTransaction() throws Exception {
    // Any seeded currency, German-formatted amount (multi-currency live, plan §1.2).
    mockMvc
        .perform(
            post(ACCOUNTS_PATH)
                .param(NAME, "Sparkonto")
                .param(TYPE, ASSET)
                .param(CURRENCY_CODE, "CHF")
                .param(OPENED_AT, OPENED_DAY)
                .param("openingBalance", "1.234,56"))
        .andExpect(status().is3xxRedirection());

    long accountId = accountIdNamed("Sparkonto");

    // Exactly one transaction, dated the opening day, whose legs sum to zero (data-model §8).
    List<Long> txnIds =
        jdbcClient
            .sql("select distinct transaction_id from posting where account_id = :id")
            .param("id", accountId)
            .query(Long.class)
            .list();
    assertThat(txnIds).hasSize(1);
    long txnId = txnIds.get(0);

    LocalDate date =
        jdbcClient
            .sql("select date from transaction where transaction_id = :id")
            .param("id", txnId)
            .query(LocalDate.class)
            .single();
    assertThat(date).isEqualTo(LocalDate.of(2026, 7, 1));

    BigDecimal legSum =
        jdbcClient
            .sql("select sum(amount) from posting where transaction_id = :id")
            .param("id", txnId)
            .query(BigDecimal.class)
            .single();
    assertThat(legSum).isEqualByComparingTo("0");

    BigDecimal accountBalance =
        jdbcClient
            .sql("select sum(amount) from posting where account_id = :id")
            .param("id", accountId)
            .query(BigDecimal.class)
            .single();
    assertThat(accountBalance).isEqualByComparingTo("1234.56");

    // The counter-leg hits the CHF Opening Balances leaf (per-currency, data-model §6.5).
    String counterLeafName =
        jdbcClient
            .sql(
                """
                select a.name
                from posting p
                join account a on p.account_id = a.account_id
                where p.transaction_id = :id and p.account_id <> :accountId
                """)
            .param("id", txnId)
            .param("accountId", accountId)
            .query(String.class)
            .single();
    assertThat(counterLeafName).isEqualTo("Opening Balances CHF");
  }

  @Test
  void editRenamesAndRecolours() throws Exception {
    mockMvc
        .perform(
            post(ACCOUNTS_PATH)
                .param(NAME, GIRO)
                .param(TYPE, ASSET)
                .param(CURRENCY_CODE, EUR)
                .param(OPENED_AT, OPENED_DAY))
        .andExpect(status().is3xxRedirection());
    long accountId = accountIdNamed(GIRO);

    mockMvc
        .perform(get(ACCOUNT_PATH_PREFIX + accountId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Close this account")));

    mockMvc
        .perform(post(ACCOUNT_PATH_PREFIX + accountId).param(NAME, "Girokonto").param("hue", "30"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(ACCOUNTS_PATH));

    mockMvc.perform(get(ACCOUNTS_PATH)).andExpect(content().string(containsString("Girokonto")));
    Integer hue =
        jdbcClient
            .sql("select hue from account where account_id = :id")
            .param("id", accountId)
            .query(Integer.class)
            .single();
    assertThat(hue).isEqualTo(30);
  }

  @Test
  void closedAccountStaysListedTaggedClosed() throws Exception {
    mockMvc
        .perform(
            post(ACCOUNTS_PATH)
                .param(NAME, ALTES_KONTO)
                .param(TYPE, "liability")
                .param(CURRENCY_CODE, EUR)
                .param(OPENED_AT, OPENED_DAY))
        .andExpect(status().is3xxRedirection());

    mockMvc
        .perform(post(ACCOUNT_PATH_PREFIX + accountIdNamed(ALTES_KONTO) + "/close"))
        .andExpect(status().is3xxRedirection());

    mockMvc
        .perform(get(ACCOUNTS_PATH))
        .andExpect(content().string(containsString(ALTES_KONTO)))
        .andExpect(content().string(containsString("closed")));
  }

  @Test
  void reopeningClosedAccountPutsItBackIntoDailyUse() throws Exception {
    mockMvc
        .perform(
            post(ACCOUNTS_PATH)
                .param(NAME, ALTES_KONTO)
                .param(TYPE, "liability")
                .param(CURRENCY_CODE, EUR)
                .param(OPENED_AT, OPENED_DAY))
        .andExpect(status().is3xxRedirection());
    long accountId = accountIdNamed(ALTES_KONTO);

    mockMvc
        .perform(post(ACCOUNT_PATH_PREFIX + accountId + "/close"))
        .andExpect(status().is3xxRedirection());
    mockMvc
        .perform(post(ACCOUNT_PATH_PREFIX + accountId + "/reopen"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(ACCOUNTS_PATH));

    Optional<LocalDate> closedAt =
        jdbcClient
            .sql("select closed_at from account where account_id = :id")
            .param("id", accountId)
            .query(LocalDate.class)
            .optional();
    assertThat(closedAt).isEmpty();

    mockMvc
        .perform(get(ACCOUNTS_PATH))
        .andExpect(content().string(containsString(ALTES_KONTO)))
        .andExpect(content().string(not(containsString("closed"))));
  }
}
