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
 * Integration tier (plan §1.5): the stage-7b entry dock driven through its controllers against real
 * Postgres — the acceptance surface for "enter a transaction from the dock". It proves the register
 * renders the dock, a commit records the transaction and repaints the rows (the new row appears
 * with the correct balance), category resolve returns the hidden id, and the ghost endpoint
 * suggests a payee's most-common category.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container — including the write-once
 * base-currency set in {@code @BeforeEach}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterEntryScreenIntegrationTest {

  private static final String REGISTER_PATH = "/register";
  private static final String ENTRY_PATH = "/register/entry";
  private static final String EUR = "EUR";
  private static final String OPEN_DAY = "2026-01-01";

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

  private long insertCategory(String name) {
    return accountService.insertLeaf(name, "expense", null, EUR).accountId();
  }

  @Test
  void registerRendersTheEntryDockWithItsPickers() throws Exception {
    openAccount("Cash", "100");
    insertCategory("Food");

    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("New transaction")))
        .andExpect(content().string(containsString("id=\"entry-dock\"")))
        // The pickers are native datalists (no bespoke JS — CLAUDE.md §1.6).
        .andExpect(content().string(containsString("list=\"entry-payee-options\"")))
        .andExpect(content().string(containsString("list=\"entry-category-options\"")))
        // The commit endpoint and repaint target are wired as literal hx-* attributes; the target
        // is
        // the parse-safe rows region div (a bare tbody can't be an htmx swap target).
        .andExpect(content().string(containsString("hx-post=\"/register/entry\"")))
        .andExpect(content().string(containsString("hx-target=\"#register-body\"")));
  }

  @Test
  void committingSimpleExpenseAppendsTheRowWithTheCorrectBalance() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("payeeText", "Rewe")
                .param("amount", "20")
                .param("categoryId", String.valueOf(food))
                .param("note", "lunch")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // The repainted rows body carries the new Food row and Cash's running balance after it
        // (500 − 20 = 480,00, German-formatted, base bare).
        .andExpect(content().string(containsString("id=\"register-rows\"")))
        .andExpect(content().string(containsString("Rewe")))
        .andExpect(content().string(containsString("Food")))
        .andExpect(content().string(containsString("480,00")))
        // The dock is reset via its out-of-band swap.
        .andExpect(content().string(containsString("hx-swap-oob=\"true\"")));
  }

  @Test
  void expenseSignIsResolvedFromTheCategoryWithoutTyping() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");

    // Amount typed bare "20"; the expense category makes it an outflow → balance drops.
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "20")
                .param("categoryId", String.valueOf(food))
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("-20,00")))
        .andExpect(content().string(containsString("480,00")));
  }

  @Test
  void backdatedInsertRethreadsTheBalancesBelowIt() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");

    // A later spend first.
    commitSpend(cash, food, "2026-03-01", "100");
    // Then a backdated one — the later row's balance must re-thread (500 −50 −100 = 350 at the
    // end).
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "50")
                .param("categoryId", String.valueOf(food))
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // Both the backdated row's balance (450,00) and the re-threaded later row's (350,00)
        // appear.
        .andExpect(content().string(containsString("450,00")))
        .andExpect(content().string(containsString("350,00")));
  }

  @Test
  void commitReRendersTheDockWithAnErrorOnBadInput() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "not a number")
                .param("categoryId", String.valueOf(food))
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // The dock re-renders carrying the message; the rows body is not swapped.
        .andExpect(content().string(containsString("id=\"entry-dock\"")))
        .andExpect(content().string(not(containsString("id=\"register-rows\""))));
  }

  // ── edit mode & void (plan stage 7c, register §3.1) ──────────────────────────

  @Test
  void editLoadsTheRowIntoTheDockInEditMode() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");
    commitSpend(cash, food, "2026-02-01", "20");
    long txnId = latestTransactionId();

    mockMvc
        .perform(get("/register/edit/" + txnId).param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // Edit mode: the title, the hidden transaction id, the Save/Void affordances, and the
        // pre-filled amount and category the re-save round-trips.
        .andExpect(content().string(containsString("Edit transaction")))
        .andExpect(content().string(containsString("name=\"transactionId\"")))
        .andExpect(content().string(containsString("value=\"" + txnId + "\"")))
        .andExpect(content().string(containsString(">Save<")))
        .andExpect(content().string(containsString("/register/void")))
        .andExpect(content().string(containsString("value=\"20,00\"")))
        .andExpect(content().string(containsString("Food")));
  }

  @Test
  void savingAnEditReThreadsTheBalanceInPlace() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");
    commitSpend(cash, food, "2026-02-01", "20"); // 500 − 20 = 480
    long txnId = latestTransactionId();

    // Re-save the same row with a bigger amount: it re-threads in place, not a second transaction.
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("transactionId", String.valueOf(txnId))
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "50")
                .param("categoryId", String.valueOf(food))
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"register-rows\"")))
        .andExpect(content().string(containsString("450,00")))
        .andExpect(content().string(not(containsString("480,00"))))
        // The dock resets to new mode via its out-of-band swap.
        .andExpect(content().string(containsString("hx-swap-oob=\"true\"")));
    // Exactly one non-opening transaction remains — the edit updated it in place.
    assertThat(spendTransactionCount()).isEqualTo(1L);
  }

  @Test
  void voidRemovesTheRowAndRepaintsTheThread() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");
    commitSpend(cash, food, "2026-02-01", "20");
    long txnId = latestTransactionId();

    mockMvc
        .perform(
            post("/register/void")
                .param("transactionId", String.valueOf(txnId))
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // The Food row is gone; Cash is back to its opening 500,00 and the −20 spend is not shown.
        .andExpect(content().string(containsString("500,00")))
        .andExpect(content().string(not(containsString("-20,00"))))
        .andExpect(content().string(containsString("hx-swap-oob=\"true\"")));
    assertThat(spendTransactionCount()).isEqualTo(0L);
  }

  @Test
  void editRefusesAnOpeningBalanceWithMessage() throws Exception {
    long cash = openAccount("Cash", "500");
    long openingTxn = openingBalanceTransactionId();

    mockMvc
        .perform(get("/register/edit/" + openingTxn).param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // Not editable in the dock yet: the dock stays in new mode carrying the explanation.
        .andExpect(content().string(containsString("New transaction")))
        .andExpect(content().string(containsString("cannot be edited")))
        .andExpect(content().string(not(containsString("name=\"transactionId\""))));
  }

  /** The most recent live transaction — the one a commit just created. */
  private long latestTransactionId() {
    return jdbcClient
        .sql("select max(transaction_id) from transaction where deleted_at is null")
        .query(Long.class)
        .single();
  }

  /** The opening-balance transaction (an equity leg — not dock-editable). */
  private long openingBalanceTransactionId() {
    return jdbcClient
        .sql(
            "select transaction_id from transaction where note = 'Opening balance'"
                + " order by transaction_id desc limit 1")
        .query(Long.class)
        .single();
  }

  /** Live transactions that are not opening balances — the ones the dock creates. */
  private long spendTransactionCount() {
    return jdbcClient
        .sql(
            "select count(*) from transaction where deleted_at is null"
                + " and (note is null or note <> 'Opening balance')")
        .query(Long.class)
        .single();
  }

  @Test
  void categoryResolveReturnsTheHiddenIdForAnExistingCategory() throws Exception {
    long food = insertCategory("Food");

    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "Food"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"categoryId\"")))
        .andExpect(content().string(containsString("value=\"" + food + "\"")));
  }

  @Test
  void categoryResolveReturnsAnErrorForAnUnknownBareName() throws Exception {
    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "Nonexistent"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Parent - Child")))
        // No id, so the dock cannot commit an unresolved category.
        .andExpect(content().string(containsString("value=\"\"")));
  }

  @Test
  void ghostSuggestsThePayeesMostCommonCategory() throws Exception {
    long cash = openAccount("Cash", "500");
    long fuel = insertCategory("Fuel");
    commitSpendWithPayee(cash, fuel, "Shell");

    mockMvc
        .perform(get("/register/ghost").param("payeeText", "Shell"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Fuel")));
  }

  @Test
  void repickingTheSamePayeeReusesItInsteadOfCreatingDuplicate() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");

    // Commit twice with the same payee text (a picked datalist value).
    commitWithPayee(cash, food, "Rewe - Dortmund - Germany", "2026-02-01");
    commitWithPayee(cash, food, "Rewe - Dortmund - Germany", "2026-02-02");

    // The two commits add exactly one payee — the second reuses the first, not a duplicate per
    // commit (register §3.4). Asserted as a delta so a reused container's prior rows don't matter.
    assertThat(payeeCount()).isEqualTo(1L);
  }

  private long payeeCount() {
    return jdbcClient
        .sql("select count(*) from payee where deleted_at is null")
        .query(Long.class)
        .single();
  }

  @Test
  void registerShowsPayeeCityAndCountrySoSameNamedPayeesAreDistinct() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");
    commitWithPayee(cash, food, "Rewe - Dortmund - Germany", "2026-02-01");

    mockMvc
        .perform(get(REGISTER_PATH).param("accountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // The register row's payee shows the composed Name · City · Country (register §3.4).
        .andExpect(content().string(containsString("Rewe · Dortmund · Germany")));
  }

  @Test
  void ghostReturnsNothingForAnUnknownPayee() throws Exception {
    mockMvc
        .perform(get("/register/ghost").param("payeeText", "Brand New Kiosk"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("auto"))));
  }

  private void commitSpend(long account, long category, String date, String amount)
      throws Exception {
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", date)
                .param("accountId", String.valueOf(account))
                .param("amount", amount)
                .param("categoryId", String.valueOf(category))
                .param("viewAccountId", String.valueOf(account)))
        .andExpect(status().isOk());
  }

  private void commitWithPayee(long account, long category, String payeeText, String date)
      throws Exception {
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", date)
                .param("accountId", String.valueOf(account))
                .param("payeeText", payeeText)
                .param("amount", "20")
                .param("categoryId", String.valueOf(category))
                .param("viewAccountId", String.valueOf(account)))
        .andExpect(status().isOk());
  }

  private void commitSpendWithPayee(long account, long category, String payee) throws Exception {
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(account))
                .param("payeeText", payee)
                .param("amount", "50")
                .param("categoryId", String.valueOf(category))
                .param("viewAccountId", String.valueOf(account)))
        .andExpect(status().isOk());
  }
}
