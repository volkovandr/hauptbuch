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
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): the stage-7c.2 split panel driven through its controllers against
 * real Postgres — the acceptance surface for "split a receipt across categories". It proves the
 * panel opens from the dock, a mixed expense+income split commits to one balanced transaction with
 * the funding leg absorbing the signed sum (the mixed-split rule, 2026-07-09), a net-zero receipt
 * still records, add-line re-renders with "the rest", and an existing split reloads into the panel.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container — including the write-once
 * base-currency set in {@code @BeforeEach}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterSplitScreenIntegrationTest {

  private static final String OPEN_PATH = "/register/split";
  private static final String COMMIT_PATH = "/register/split/commit";
  private static final String EUR = "EUR";
  private static final String OPEN_DAY = "2026-01-01";
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
    Account account =
        accountService.openAccount(
            new AccountDraft(
                name,
                "asset",
                null,
                EUR,
                LocalDate.parse(OPEN_DAY),
                new BigDecimal(openingBalance)));
    return account.accountId();
  }

  private long insertCategory(String name, String type) {
    return accountService.insertLeaf(name, type, null, EUR).accountId();
  }

  @Test
  void openSeedsThePanelFromTheDocksSingleLine() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food", "expense");

    mockMvc
        .perform(
            post(OPEN_PATH)
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("amount", "20")
                .param("categoryId", String.valueOf(food))
                .param("categoryText", "Food")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // The panel replaces the dock, seeded with the committed line at its full amount.
        .andExpect(content().string(containsString("data-split-panel")))
        .andExpect(content().string(containsString("Split transaction")))
        .andExpect(content().string(containsString("value=\"20\"")))
        // The reference total is a visible, editable field (not hidden — a fixed UI issue).
        .andExpect(content().string(containsString("id=\"split-total\"")))
        .andExpect(content().string(containsString("data-split-total-input")))
        // It opens balanced against the seed magnitude.
        .andExpect(content().string(containsString("remaining 0,00")));
  }

  @Test
  void commaAmountOnOneLineDoesNotSpawnExtraLines() throws Exception {
    // Regression (fixed UI issue): Spring's list binding split a single "20,50" into ["20","50"],
    // conjuring phantom lines. Reading the raw params keeps the one line intact; add-line then
    // makes
    // exactly two lines (the original plus "the rest").
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food", "expense");

    mockMvc
        .perform(
            post("/register/split/add-line")
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("total", "20,50")
                .param("categoryText", "Food")
                .param("lineCategoryId", String.valueOf(food))
                .param("lineCategoryType", "expense")
                .param("lineAmount", "20,50")
                .param("lineNote", "")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"20,50\"")))
        // The one entered line plus the one appended — not three from a phantom comma split.
        .andExpect(content().string(occursExactly("class=\"split-line\"", 2)));
  }

  @Test
  void cancelReturnsToTheDockPreFilledNotBlank() throws Exception {
    // Regression (fixed UI issue): Cancel used to reset the dock to empty, discarding the date,
    // account, payee and amount. It now returns a pre-filled NEW dock.
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food", "expense");

    mockMvc
        .perform(
            post("/register/split/cancel")
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("payeeText", "Rewe")
                .param("total", "20,00")
                .param("categoryText", "Food")
                .param("lineCategoryId", String.valueOf(food))
                .param("lineAmount", "20")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // A pre-filled NEW dock: the header and first line are carried, but it is not edit mode.
        .andExpect(content().string(containsString("New transaction")))
        .andExpect(content().string(containsString("value=\"Rewe\"")))
        .andExpect(content().string(containsString("value=\"20\"")))
        .andExpect(content().string(containsString("value=\"Food\"")))
        .andExpect(content().string(not(containsString("name=\"transactionId\""))));
  }

  /** A Hamcrest matcher asserting {@code needle} occurs exactly {@code times} in the content. */
  private static org.hamcrest.Matcher<String> occursExactly(String needle, int times) {
    return new org.hamcrest.CustomMatcher<>("'" + needle + "' exactly " + times + " times") {
      @Override
      public boolean matches(Object actual) {
        if (!(actual instanceof String haystack)) {
          return false;
        }
        int count = 0;
        for (int from = haystack.indexOf(needle);
            from >= 0;
            from = haystack.indexOf(needle, from + needle.length())) {
          count++;
        }
        return count == times;
      }
    };
  }

  @Test
  void mixedExpenseAndIncomeCommitToOneBalancedTransaction() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food", "expense");
    long deposit = insertCategory("Deposit", "income");

    // Food €20 (expense) + bottle-deposit €3 return (income): the funding leg is 500 − 20 + 3 =
    // 483.
    mockMvc
        .perform(
            post(COMMIT_PATH)
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("lineCategoryId", String.valueOf(food), String.valueOf(deposit))
                .param("lineAmount", "20", "3")
                .param("lineNote", "", "")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"register-rows\"")))
        .andExpect(content().string(containsString("Food")))
        .andExpect(content().string(containsString("Deposit")))
        .andExpect(content().string(containsString("483,00")))
        // The dock is reset via its out-of-band swap.
        .andExpect(content().string(containsString("hx-swap-oob=\"true\"")));

    assertThat(spendTransactionCount()).isEqualTo(1L);
    // Three legs, summing to zero: Cash −17, Food +20, Deposit −3.
    assertThat(legCount(latestTransactionId())).isEqualTo(3L);
    assertThat(legSum(latestTransactionId())).isEqualByComparingTo("0");
  }

  @Test
  void netZeroReceiptStillRecords() throws Exception {
    long cash = openAccount("Cash", "500");
    long cola = insertCategory("Snacks", "expense");
    long deposit = insertCategory("Deposit", "income");

    // Return five bottles (income 5), take one Cola (expense 5), pay nothing: balance unchanged.
    mockMvc
        .perform(
            post(COMMIT_PATH)
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("lineCategoryId", String.valueOf(cola), String.valueOf(deposit))
                .param("lineAmount", "5", "5")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("500,00")));

    // The transaction is recorded even though nothing moved through the account (owner,
    // 2026-07-09).
    assertThat(spendTransactionCount()).isEqualTo(1L);
    assertThat(legSum(latestTransactionId())).isEqualByComparingTo("0");
  }

  @Test
  void addLineReRendersWithAnExtraLineDefaultingToTheRest() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food", "expense");

    // One expense line of 15 against a reference total of 20 → the added line defaults to "the
    // rest"
    // (5,00).
    mockMvc
        .perform(
            post("/register/split/add-line")
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("total", "20,00")
                .param("categoryText", "Food")
                .param("lineCategoryId", String.valueOf(food))
                .param("lineCategoryType", "expense")
                .param("lineAmount", "15")
                .param("lineNote", "")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-split-panel")))
        .andExpect(content().string(containsString("value=\"15\"")))
        .andExpect(content().string(containsString("value=\"5,00\"")));
  }

  @Test
  void commitReRendersThePanelWithAnErrorForaLineWithoutaCategory() throws Exception {
    long cash = openAccount("Cash", "500");

    mockMvc
        .perform(
            post(COMMIT_PATH)
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("lineCategoryId", "")
                .param("lineAmount", "20")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-split-panel")))
        .andExpect(content().string(containsString("needs a category")))
        // The rows body is not swapped on error.
        .andExpect(content().string(not(containsString("id=\"register-rows\""))));
  }

  @Test
  void editLoadsAnExistingSplitBackIntoThePanel() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food", "expense");
    long deposit = insertCategory("Deposit", "income");
    mockMvc
        .perform(
            post(COMMIT_PATH)
                .param("date", SPEND_DAY)
                .param("accountId", String.valueOf(cash))
                .param("lineCategoryId", String.valueOf(food), String.valueOf(deposit))
                .param("lineAmount", "20", "3")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk());
    long txnId = latestTransactionId();

    mockMvc
        .perform(get("/register/edit/" + txnId).param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // A split reloads into the panel (not the simple dock), with its lines and id pre-filled.
        .andExpect(content().string(containsString("data-split-panel")))
        .andExpect(content().string(containsString("Edit split")))
        .andExpect(content().string(containsString("name=\"transactionId\"")))
        .andExpect(content().string(containsString("Food")))
        .andExpect(content().string(containsString("Deposit")));
  }

  private long latestTransactionId() {
    return jdbcClient
        .sql("select max(transaction_id) from transaction where deleted_at is null")
        .query(Long.class)
        .single();
  }

  private long spendTransactionCount() {
    return jdbcClient
        .sql(
            "select count(*) from transaction where deleted_at is null"
                + " and (note is null or note <> 'Opening balance')")
        .query(Long.class)
        .single();
  }

  private long legCount(long transactionId) {
    return jdbcClient
        .sql("select count(*) from posting where transaction_id = :id")
        .param("id", transactionId)
        .query(Long.class)
        .single();
  }

  private BigDecimal legSum(long transactionId) {
    return jdbcClient
        .sql("select coalesce(sum(amount), 0) from posting where transaction_id = :id")
        .param("id", transactionId)
        .query(BigDecimal.class)
        .single();
  }
}
