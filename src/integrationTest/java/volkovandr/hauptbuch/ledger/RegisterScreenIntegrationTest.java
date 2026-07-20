package volkovandr.hauptbuch.ledger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.PersonProvisioningService;

/**
 * Integration tier (plan §1.5): the stage-7a register screen rendered through the controller
 * against real Postgres — the stage's acceptance surface (register §2.4–§2.10). The register query
 * itself is covered in {@link RegisterSqlLogicTest}; this test asserts the <em>rendered page</em>:
 * the fixed columns, the same-hue zebra styles, German money formatting, the summarised Category
 * cell, filters, and the newest-at-bottom scroll hook.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container — including the write-once
 * base-currency set that {@code @BeforeEach} performs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterScreenIntegrationTest {

  private static final String REGISTER_PATH = "/register";
  private static final String EUR = "EUR";
  private static final String EXPENSE = "expense";
  private static final String CONFIRMED = "confirmed";
  private static final String OPEN_DAY = "2026-01-01";
  private static final String CASH = "Cash";
  private static final String GIRO = "Giro";
  private static final String FOOD = "Food";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired LedgerService ledgerService;
  @Autowired SettingsService settingsService;
  @Autowired PersonProvisioningService personProvisioningService;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  /** Open a real asset account with an opening balance (a live register row on day one). */
  private long openAccount(String name, String currency, String openingBalance) {
    Account account =
        accountService.openAccount(
            new AccountDraft(
                name,
                "asset",
                null,
                currency,
                LocalDate.parse(OPEN_DAY),
                new BigDecimal(openingBalance)));
    return account.accountId();
  }

  /** A fresh expense leaf to spend against. */
  private long insertCategory(String name, String currency) {
    return accountService.insertLeaf(name, EXPENSE, null, currency).accountId();
  }

  /** Record a single-category spend: {@code account −magnitude / category +magnitude}. */
  private void spend(String date, long account, long category, String magnitude) {
    ledgerService.recordTransaction(
        new TransactionDraft(
            LocalDate.parse(date),
            null,
            "spend",
            CONFIRMED,
            List.of(
                PostingDraft.of(account, new BigDecimal("-" + magnitude)),
                PostingDraft.of(category, new BigDecimal(magnitude)))));
  }

  @Test
  void screenRendersTheFilterAndAnEmptyStateOnBook() throws Exception {
    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Transaction register")))
        .andExpect(content().string(containsString("No transactions in this view")));
  }

  @Test
  void openingBalanceRowRendersWithGermanAmountAndBalance() throws Exception {
    openAccount(CASH, EUR, "1234.56");

    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(CASH)))
        // The opening balance renders bare (base currency) and German-formatted, as both the
        // amount and the running balance of the sole row.
        .andExpect(content().string(containsString("1.234,56")))
        // The scroll hook that anchors newest-at-bottom (register §2.1).
        .andExpect(content().string(containsString("data-scroll-bottom")));
  }

  @Test
  void expenseRowSummarisesTheCounterpartCategory() throws Exception {
    long cash = openAccount(CASH, EUR, "500");
    long food = insertCategory(FOOD, EUR);
    spend("2026-02-01", cash, food, "20");

    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        // The Cash leg is the row; its Category cell names the counterpart Food leg (register
        // §2.6).
        .andExpect(content().string(containsString(FOOD)))
        .andExpect(content().string(containsString("cat-chip")));
  }

  @Test
  void transferBetweenTwoAccountsShowsBothLegsAndTheTransferChip() throws Exception {
    long cash = openAccount(CASH, EUR, "500");
    long giro = openAccount(GIRO, EUR, "0");
    // Move 100 from Giro to Cash — a transfer between two own accounts.
    ledgerService.recordTransaction(
        new TransactionDraft(
            LocalDate.parse("2026-03-01"),
            null,
            "transfer",
            CONFIRMED,
            List.of(
                PostingDraft.of(giro, new BigDecimal("-100")),
                PostingDraft.of(cash, new BigDecimal("100")))));

    // Viewing ONLY Cash: the Giro leg is now the counterpart → the ⇄ transfer chip (register §2.6).
    // (With both accounts viewed, a transfer is two rows each on its own thread, no counterpart —
    // that is the correct default-filter behaviour, so the chip only surfaces on a single-account
    // view. The single-leg filtering itself is pinned in RegisterSqlLogicTest.)
    mockMvc
        .perform(get(REGISTER_PATH).param("accountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("cat-chip--transfer")))
        .andExpect(content().string(containsString("Giro")));
  }

  @Test
  void hueDrivenZebraStyleIsRenderedFromTheStoredAccountHue() throws Exception {
    openAccount(CASH, EUR, "100");

    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        // The row carries an inline background derived from the account's stored hue (register
        // §2.8).
        .andExpect(content().string(matchesRegex("(?s).*background: hsl\\(\\d+ \\d+% \\d+%\\).*")));
  }

  @Test
  void accountFilterNarrowsToTheSelectedAccount() throws Exception {
    long cash = openAccount(CASH, EUR, "100");
    openAccount(GIRO, EUR, "999");

    // Filtering to Cash only: the Giro opening balance (999,00) must not appear.
    mockMvc
        .perform(get(REGISTER_PATH).param("accountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(CASH)))
        .andExpect(content().string(not(containsString("999,00"))));
  }

  // ── Person legs (register §2.6, plan stage 8c) ────────────────────────────

  /** Auto-provision a person's per-currency debt leaf and return its account id. */
  private long provisionPersonLeaf(String name, String currency) {
    return personProvisioningService.ensureLeaf(name, currency, false).accountId();
  }

  /** Record a balanced two-leg transaction (debit +magnitude / credit −magnitude). */
  private void twoLeg(String date, long debit, long credit, String magnitude) {
    ledgerService.recordTransaction(
        new TransactionDraft(
            LocalDate.parse(date),
            null,
            "person",
            CONFIRMED,
            List.of(
                PostingDraft.of(debit, new BigDecimal(magnitude)),
                PostingDraft.of(credit, new BigDecimal("-" + magnitude)))));
  }

  @Test
  void personCounterpartRendersAsAnArrowChipWithTheResolvedName() throws Exception {
    long cash = openAccount(CASH, EUR, "500");
    long max = provisionPersonLeaf("Max", EUR);
    // You fronted 10 for Max: Cash −10, Max +10. Viewing Cash, Max is the counterpart → a person
    // chip, → Max (a debit — Max owes you), showing the real name, never the cosmetic leaf.
    twoLeg("2026-04-01", max, cash, "10");

    mockMvc
        .perform(get(REGISTER_PATH).param("accountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("cat-chip--person")))
        .andExpect(content().string(containsString("Max")))
        .andExpect(content().string(not(containsString("personal.EUR"))));
  }

  @Test
  void personFundedExpenseShowsThePersonOnTheAccountSideAndInTheFilter() throws Exception {
    long food = insertCategory(FOOD, EUR);
    long max = provisionPersonLeaf("Max", EUR);
    // Max paid for a pure expense of yours: Food +10, Max −10. No cash leg, so the person's debt
    // leaf is the row's own account (register §2.6 pattern 3) and appears on the default view.
    twoLeg("2026-05-01", food, max, "10");

    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        // The person's real name shows on the Account side; the cosmetic leaf name never leaks.
        .andExpect(content().string(containsString("Max")))
        .andExpect(content().string(not(containsString("personal.EUR"))))
        // The counterpart is the ordinary expense category.
        .andExpect(content().string(containsString(FOOD)))
        // And the person is offered in the account filter as `Max (EUR)` (plan stage 8c).
        .andExpect(content().string(containsString("Max (EUR)")));
  }
}
