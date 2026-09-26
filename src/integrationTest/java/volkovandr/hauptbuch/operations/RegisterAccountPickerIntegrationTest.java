package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Integration tier (plan §1.5): the register's own-account pickers against real Postgres once
 * accounts are nested (issue transaction-register-ui/25) — the dock's Account datalist and the
 * transfer targets offer posting leaves only, by their full {@code Parent - Leaf} path; a group is
 * refused inline rather than at commit; and an edit-mode pre-fill resolves back to the same
 * account.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterAccountPickerIntegrationTest {

  private static final String REGISTER_PATH = "/register";
  private static final String ENTRY_PATH = "/register/entry";
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String DAY = "2026-01-01";
  private static final String SPEND_DAY = "2026-02-01";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long openAccount(String name, String openingBalance) {
    return open(name, null, EUR, new BigDecimal(openingBalance));
  }

  private long openChild(String name, long parentId) {
    return open(name, parentId, EUR, BigDecimal.ZERO);
  }

  private long open(String name, Long parentId, String currency, BigDecimal openingBalance) {
    return accountService
        .openAccount(
            new AccountDraft(
                name, "asset", parentId, currency, LocalDate.parse(DAY), openingBalance))
        .accountId();
  }

  private BigDecimal amountOn(long transactionId, long accountId) {
    return jdbcClient
        .sql("select sum(amount) from posting where transaction_id = :t and account_id = :a")
        .param("t", transactionId)
        .param("a", accountId)
        .query(BigDecimal.class)
        .single();
  }

  private long latestTransactionId() {
    return jdbcClient
        .sql("select max(transaction_id) from transaction where deleted_at is null")
        .query(Long.class)
        .single();
  }

  @Test
  void ownAccountPickersOfferNestedAccountsByPathAndOmitTheirGroup() throws Exception {
    long bank = openAccount("BankAaa", "0");
    openChild("Credit card", bank);

    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"BankAaa - Credit card (EUR)\"")))
        .andExpect(content().string(containsString("value=\"To → BankAaa - Credit card (EUR)\"")))
        .andExpect(content().string(containsString("value=\"From ← BankAaa - Credit card (EUR)\"")))
        // The group is not offered — posting to it would be refused (leaves-only).
        .andExpect(content().string(not(containsString("value=\"BankAaa (EUR)\""))))
        .andExpect(content().string(not(containsString("value=\"To → BankAaa (EUR)\""))));
  }

  @Test
  void accountFieldRefusesGroupButResolvesItsLeafByPath() throws Exception {
    long bank = openAccount("BankAaa", "0");
    long card = openChild("Credit card", bank);

    mockMvc
        .perform(post("/register/account/resolve").param("accountText", "BankAaa"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("is a group")))
        .andExpect(content().string(not(containsString("name=\"accountId\""))));
    mockMvc
        .perform(
            post("/register/account/resolve").param("accountText", "BankAaa - Credit card (EUR)"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"accountId\"")))
        .andExpect(content().string(containsString("value=\"" + card + "\"")));
  }

  @Test
  void accountFieldRefusesAmbiguousNameListingTheCandidates() throws Exception {
    openChild("Credit card", openAccount("BankAaa", "0"));
    long bbbCard = openChild("Credit card", openAccount("BankBbb", "0"));

    mockMvc
        .perform(post("/register/account/resolve").param("accountText", "Credit card"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("BankAaa - Credit card (EUR)")))
        .andExpect(content().string(containsString("BankBbb - Credit card (EUR)")))
        .andExpect(content().string(not(containsString("name=\"accountId\""))));
    mockMvc
        .perform(post("/register/account/resolve").param("accountText", "BankBbb - Credit card"))
        .andExpect(content().string(containsString("value=\"" + bbbCard + "\"")));
  }

  @Test
  void transferTargetRefusesGroup() throws Exception {
    openChild("Credit card", openAccount("BankAaa", "0"));

    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "To → BankAaa"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("is a group")))
        .andExpect(content().string(not(containsString("name=\"transferDirection\""))));
  }

  @Test
  void editingTransferToNestedAccountRoundTripsThroughItsPrefilledLabels() throws Exception {
    long cash = openAccount("Cash", "500");
    long card = openChild("Credit card", openAccount("BankAaa", "0"));
    commitTransfer(cash, card);
    long txnId = latestTransactionId();

    mockMvc
        .perform(get("/register/edit/" + txnId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"Cash (EUR)\"")))
        .andExpect(content().string(containsString("To → BankAaa - Credit card (EUR)")));
    // Each pre-filled label resolves back to the account it came from...
    mockMvc
        .perform(post("/register/account/resolve").param("accountText", "Cash (EUR)"))
        .andExpect(content().string(containsString("value=\"" + cash + "\"")));
    mockMvc
        .perform(
            post("/categories/resolve").param("categoryText", "To → BankAaa - Credit card (EUR)"))
        .andExpect(content().string(containsString("value=\"" + card + "\"")));
    // ...so re-saving the untouched edit books the same legs, in place.
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("transactionId", String.valueOf(txnId))
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("amount", "20")
                .param("categoryId", String.valueOf(card))
                .param("transferDirection", "TO")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk());

    assertThat(latestTransactionId()).isEqualTo(txnId);
    assertThat(amountOn(txnId, cash)).isEqualByComparingTo("-20");
    assertThat(amountOn(txnId, card)).isEqualByComparingTo("20");
  }

  @Test
  void transferToOneOfTwoSameNamedAccountsPrefillsTheCurrencyThatTellsThemApart() throws Exception {
    long cash = open("Cash", null, CHF, BigDecimal.ZERO);
    open("Card", null, EUR, BigDecimal.ZERO);
    long chfCard = open("Card", null, CHF, BigDecimal.ZERO);
    commitTransfer(cash, chfCard);

    mockMvc
        .perform(get("/register/edit/" + latestTransactionId()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("To → Card (CHF)")));
    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "To → Card (CHF)"))
        .andExpect(content().string(containsString("value=\"" + chfCard + "\"")));
  }

  @Test
  void splitTransferLineToNestedAccountReloadsByLabelAndReSaves() throws Exception {
    long cash = openAccount("Cash", "500");
    long card = openChild("Credit card", openAccount("BankAaa", "0"));
    long food = accountService.insertLeaf("Food", "expense", null, EUR).accountId();
    commitSplit(null, cash, food, card);
    long txnId = latestTransactionId();

    mockMvc
        .perform(get("/register/edit/" + txnId).param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-split-panel")))
        .andExpect(content().string(containsString("To → BankAaa - Credit card (EUR)")));
    mockMvc
        .perform(
            post("/categories/resolve").param("categoryText", "To → BankAaa - Credit card (EUR)"))
        .andExpect(content().string(containsString("value=\"" + card + "\"")));
    commitSplit(txnId, cash, food, card);

    assertThat(latestTransactionId()).isEqualTo(txnId);
    assertThat(amountOn(txnId, card)).isEqualByComparingTo("30");
    assertThat(amountOn(txnId, cash)).isEqualByComparingTo("-50");
  }

  /** A €20 transfer from {@code from} to {@code to}. */
  private void commitTransfer(long from, long to) throws Exception {
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(from))
                .param("amount", "20")
                .param("categoryId", String.valueOf(to))
                .param("transferDirection", "TO")
                .param("viewAccountId", String.valueOf(from)))
        .andExpect(status().isOk());
  }

  /** A split off {@code cash}: Food €20 plus €30 moved to {@code card}; new when id is null. */
  private void commitSplit(Long transactionId, long cash, long food, long card) throws Exception {
    mockMvc
        .perform(
            post("/register/split/commit")
                .param("transactionId", transactionId == null ? "" : String.valueOf(transactionId))
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("lineCategoryId", String.valueOf(food), String.valueOf(card))
                .param("lineCategoryType", "expense", "")
                .param("lineTransferDirection", "", "TO")
                .param("lineAmount", "20", "30")
                .param("total", "50")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk());
  }
}
