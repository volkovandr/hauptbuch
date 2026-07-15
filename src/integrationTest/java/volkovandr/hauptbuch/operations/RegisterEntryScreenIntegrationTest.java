package volkovandr.hauptbuch.operations;

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
    return openAccount(name, EUR, openingBalance);
  }

  private long openAccount(String name, String currencyCode, String openingBalance) {
    Account account =
        accountService.openAccount(
            new AccountDraft(
                name,
                "asset",
                null,
                currencyCode,
                LocalDate.parse(OPEN_DAY),
                new BigDecimal(openingBalance)));
    return account.accountId();
  }

  private long insertCategory(String name) {
    return accountService.insertLeaf(name, "expense", null, EUR).accountId();
  }

  private long insertTag(String name, Long parentId) {
    return jdbcClient
        .sql("insert into tag (name, parent_id) values (:n, :p) returning tag_id")
        .param("n", name)
        .param("p", parentId)
        .query(Long.class)
        .single();
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
  void resolveTagReturnsPillCarryingTheHiddenTagId() throws Exception {
    long car = insertTag("Car", null);
    long passat = insertTag("Passat", car);

    mockMvc
        .perform(post("/categories/tags/resolve").param("tagText", "car:passat"))
        .andExpect(status().isOk())
        // The pill shows the canonical label and carries the hidden id the commit submits.
        .andExpect(content().string(containsString("Car:Passat")))
        .andExpect(content().string(containsString("name=\"tagId\"")))
        .andExpect(content().string(containsString("value=\"" + passat + "\"")))
        .andExpect(content().string(containsString("data-tag-chip")));
  }

  @Test
  void resolveTagPicksTheIndexedValueWhenHtmxSendsEveryTagInput() throws Exception {
    // Regression (2026-07-15): the split panel has one tagText input per line plus the header, and
    // htmx serializes the whole form, so a chip commit sends every tagText value at once — here the
    // empty header (index 0) and empty first line (index 1) precede the typed second line (index
    // 2).
    // `index` selects the committed one, so the endpoint resolves "Trips:France-2026", not a
    // comma-joined string (which used to conjure bogus parent tags like ",,Trips").
    long trips = insertTag("Trips", null);
    long france = insertTag("France-2026", trips);

    mockMvc
        .perform(
            post("/categories/tags/resolve")
                .param("tagText", "", "", "Trips:France-2026")
                .param("index", "2"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Trips:France-2026")))
        .andExpect(content().string(containsString("value=\"" + france + "\"")));

    // No comma-prefixed junk tag was created — only the two seeded rows exist.
    Long tagCount = jdbcClient.sql("select count(*) from tag").query(Long.class).single();
    assertThat(tagCount).isEqualTo(2L);
  }

  @Test
  void committingWithTagsPersistsThemOnEveryLegAndPrefillsThemOnEdit() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");
    long car = insertTag("Car", null);
    long passat = insertTag("Passat", car);
    long trip = insertTag("Trip", null);

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "20")
                .param("categoryId", String.valueOf(food))
                .param("tagId", String.valueOf(passat))
                .param("tagId", String.valueOf(trip))
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        // The repainted Cash row shows the leg's tag chips (register §3.6).
        .andExpect(content().string(containsString("#Car:Passat")))
        .andExpect(content().string(containsString("#Trip")));

    // A transaction-level tag lands on every leg (data-model §10.2): 2 legs × 2 tags = 4 rows.
    Long tagRows = jdbcClient.sql("select count(*) from posting_tag").query(Long.class).single();
    assertThat(tagRows).isEqualTo(4L);

    long txnId =
        jdbcClient
            .sql("select transaction_id from transaction where date = :d order by transaction_id")
            .param("d", LocalDate.parse("2026-02-01"))
            .query(Long.class)
            .single();

    // Editing pre-fills the tags as pills (canonical labels), so a re-save preserves them.
    mockMvc
        .perform(get("/register/edit/" + txnId).param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Edit transaction")))
        .andExpect(content().string(containsString("Car:Passat")))
        .andExpect(content().string(containsString("Trip")));
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

  // ── cross-currency entry (register §3.5/§3.8a, plan stage 7d.1) ──────────────

  @Test
  void registerDefaultsTheCurrencyPickerToTheFundingAccountsCurrencyWithNoExtraFields()
      throws Exception {
    openAccount("Cash", "100");
    insertCategory("Food");

    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"entry-category-currency\"")))
        // The Amount label carries the funding account's own currency, unambiguous even before
        // any override (register §3.8a).
        .andExpect(content().string(containsString("Amount (EUR)")))
        // No override yet: the ≥95% single-currency path stays a single Amount field.
        .andExpect(content().string(not(containsString("name=\"categoryAmount\""))))
        .andExpect(content().string(not(containsString("name=\"baseAmount\""))));
  }

  @Test
  void currencyFieldsRelabelsTheFundingAmountFieldWhenTheAccountChangesWithoutLosingItsValue()
      throws Exception {
    long chfCard = openAccount("Cash CHF", "CHF", "500");
    insertCategory("Food");

    // Simulates the funding Account select changing to the CHF card: the Amount label switches to
    // the new account's currency, and the already-typed magnitude rides along unchanged.
    mockMvc
        .perform(
            post("/register/currency-fields")
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(chfCard))
                .param("amount", "9,10"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Amount (CHF)")))
        .andExpect(content().string(containsString("value=\"9,10\"")));
  }

  @Test
  void currencyFieldsRevealsTheCategoryAmountFieldForTwoSidedOverride() throws Exception {
    long cash = openAccount("Cash", "500");
    insertCategory("Food");

    mockMvc
        .perform(
            post("/register/currency-fields")
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "9,10")
                .param("categoryCurrencyCode", "CHF"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"categoryAmount\"")))
        .andExpect(content().string(containsString("Amount (CHF)")))
        // The funding account (EUR) is the book's base, so no separate base field is shown (§3.8a).
        .andExpect(content().string(not(containsString("name=\"baseAmount\""))));
  }

  @Test
  void currencyFieldsRevealsPrefilledBaseAmountWhenNeitherLegIsBase() throws Exception {
    long chfCard = openAccount("Cash CHF", "CHF", "500");
    insertCategory("Shopping");
    jdbcClient
        .sql(
            "insert into exchange_rate (currency_code, date, rate, source)"
                + " values ('CHF', '2026-01-01', 0.95, 'manual')")
        .update();

    mockMvc
        .perform(
            post("/register/currency-fields")
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(chfCard))
                .param("amount", "10")
                .param("categoryCurrencyCode", "USD"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"baseAmount\"")))
        // 10 CHF carried forward at the January rate (0.95) pre-fills 9,50 EUR.
        .andExpect(content().string(containsString("value=\"9,50\"")));
  }

  @Test
  void committingEurCardPurchaseOfChfPricedItemBooksBalancedAndUpdatesEurThread() throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "9,10")
                .param("categoryId", String.valueOf(food))
                .param("categoryCurrencyCode", "CHF")
                .param("categoryAmount", "10")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"register-rows\"")))
        // Cash (EUR, the book's base) drops by the entered EUR amount, not the CHF price.
        .andExpect(content().string(containsString("490,90")));

    // One balanced cross-currency transaction: Σ base_amount = 0 across its (frozen) legs.
    assertThat(spendTransactionCount()).isEqualTo(1L);
    assertThat(baseAmountSum()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void resolvedCurrencyLeafNeverAppearsInTheCategoryPickerAfterwards() throws Exception {
    // Plan stage 7d.1 follow-up bug: overriding the currency created a "Food EUR"-style leaf that
    // then leaked into the category picker as a second, confusing option alongside plain "Food".
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "9,10")
                .param("categoryId", String.valueOf(food))
                .param("categoryCurrencyCode", "CHF")
                .param("categoryAmount", "10")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk());

    // Exactly one live child of Food exists now (the auto-managed CHF leaf) — it must never be
    // individually offered in the category datalist, only "Food" itself. The category datalist's
    // <option> is empty (just a value, register §3.5); the currency picker's own CHF option (a
    // legitimate, unrelated currency choice) carries visible text, so this stays unambiguous.
    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        .andExpect(
            content().string(matchesPattern("(?s).*<option\\s+value=\"Food\"\\s*></option>.*")))
        .andExpect(
            content()
                .string(not(matchesPattern("(?s).*<option\\s+value=\"CHF\"\\s*></option>.*"))));
  }

  @Test
  void committingChfCardPurchaseOfUsdPricedItemFreezesTheConfirmedBaseAmount() throws Exception {
    long chfCard = openAccount("Cash CHF", "CHF", "500");
    long shopping = insertCategory("Shopping");

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(chfCard))
                .param("amount", "9")
                .param("categoryId", String.valueOf(shopping))
                .param("categoryCurrencyCode", "USD")
                .param("categoryAmount", "10")
                .param("baseAmount", "8,50")
                .param("viewAccountId", String.valueOf(chfCard)))
        .andExpect(status().isOk())
        // Cash CHF drops by the entered CHF amount (500 − 9 = 491,00).
        .andExpect(content().string(containsString("491,00")));

    assertThat(spendTransactionCount()).isEqualTo(1L);
    assertThat(baseAmountSum()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void committingCrossCurrencyWithoutTheCategoryAmountShowsClearErrorAndLeavesRowsUntouched()
      throws Exception {
    long cash = openAccount("Cash", "500");
    long food = insertCategory("Food");

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "9,10")
                .param("categoryId", String.valueOf(food))
                .param("categoryCurrencyCode", "CHF")
                .param("viewAccountId", String.valueOf(cash)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("CHF amount is required")))
        .andExpect(content().string(not(containsString("id=\"register-rows\""))));
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

  // ── transfers, single line (plan stage 7d.3, register §3.5/§3.8) ─────────────

  @Test
  void registerOffersTransferTargetsInTheCategoryDatalist() throws Exception {
    openAccount("Cash", "100");
    openAccount("Visa", "0");

    mockMvc
        .perform(get(REGISTER_PATH))
        .andExpect(status().isOk())
        // Every own account contributes a To → and From ← option to the Category datalist.
        .andExpect(content().string(containsString("value=\"To → Visa\"")))
        .andExpect(content().string(containsString("value=\"From ← Visa\"")));
  }

  @Test
  void resolvingTransferTargetReturnsAccountIdDirectionAndRevealTrigger() throws Exception {
    openAccount("Cash", "100");
    long visa = openAccount("Visa", "0");

    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "To → Visa"))
        .andExpect(status().isOk())
        // The counter-leg is the real account, carried in the same hidden id the commit reads, plus
        // the direction it signs the funding leg by.
        .andExpect(content().string(containsString("name=\"categoryId\"")))
        .andExpect(content().string(containsString("value=\"" + visa + "\"")))
        .andExpect(content().string(containsString("name=\"transferDirection\"")))
        .andExpect(content().string(containsString("value=\"TO\"")))
        // The reveal trigger so the dock recomputes its amount fields for a cross-currency
        // transfer — after-swap, so the recompute serialises the form with the resolved
        // id/direction already in place.
        .andExpect(header().string("HX-Trigger-After-Swap", "counterpart-resolved"));
  }

  @Test
  void resolvingTransferTargetWithUnknownAccountReturnsErrorAndNoId() throws Exception {
    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "To → Nowhere"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No account named Nowhere")))
        .andExpect(content().string(containsString("value=\"\"")))
        .andExpect(content().string(not(containsString("name=\"transferDirection\""))));
  }

  @Test
  void sameCurrencyTransferBooksBothLegsAsTwoThreads() throws Exception {
    long cash = openAccount("Cash", "500");
    long visa = openAccount("Visa", "0");

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "20")
                .param("categoryId", String.valueOf(visa))
                .param("transferDirection", "TO")
                .param("viewAccountId", String.valueOf(cash))
                .param("viewAccountId", String.valueOf(visa)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"register-rows\"")))
        // Cash −20 (500 → 480), Visa +20 (0 → 20): two rows, one per leg (register §2.6).
        .andExpect(content().string(containsString("480,00")))
        .andExpect(content().string(containsString("20,00")))
        // The Category cell shows the counterpart with a direction arrow (plan stage 7d.3): Cash's
        // row → Visa (outbound), Visa's row ← Cash (inbound). Both arrows appear.
        .andExpect(content().string(containsString("→ ")))
        .andExpect(content().string(containsString("← ")));
    assertThat(spendTransactionCount()).isEqualTo(1L);
  }

  @Test
  void fromDirectionTransferIsAnInflowToTheFundingAccount() throws Exception {
    long cash = openAccount("Cash", "500");
    long visa = openAccount("Visa", "200");

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "20")
                .param("categoryId", String.valueOf(visa))
                .param("transferDirection", "FROM")
                .param("viewAccountId", String.valueOf(cash))
                .param("viewAccountId", String.valueOf(visa)))
        .andExpect(status().isOk())
        // From ← Visa: Cash +20 (500 → 520), Visa −20 (200 → 180).
        .andExpect(content().string(containsString("520,00")))
        .andExpect(content().string(containsString("180,00")));
  }

  @Test
  void crossCurrencyTransferRevealsTheCounterpartAmountField() throws Exception {
    long cash = openAccount("Cash", "500");
    long visaChf = openAccount("Visa CHF", "CHF", "0");

    // The reveal the counterpart-resolved trigger fires: the counterpart currency comes from the
    // resolved account (CHF), not the currency selector.
    mockMvc
        .perform(
            post("/register/currency-fields")
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "20")
                .param("categoryId", String.valueOf(visaChf))
                .param("transferDirection", "TO"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"categoryAmount\"")))
        .andExpect(content().string(containsString("Amount (CHF)")));
  }

  @Test
  void crossCurrencyTransferBooksBalancedInBase() throws Exception {
    long cash = openAccount("Cash", "500");
    long visaChf = openAccount("Visa CHF", "CHF", "0");

    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", "2026-02-01")
                .param("accountId", String.valueOf(cash))
                .param("amount", "20")
                .param("categoryId", String.valueOf(visaChf))
                .param("transferDirection", "TO")
                .param("categoryAmount", "25")
                .param("viewAccountId", String.valueOf(cash))
                .param("viewAccountId", String.valueOf(visaChf)))
        .andExpect(status().isOk())
        // Cash EUR (base) drops by the entered EUR amount (500 − 20 = 480,00).
        .andExpect(content().string(containsString("480,00")));

    // One balanced cross-currency transfer: Σ base_amount = 0 across the frozen legs.
    assertThat(spendTransactionCount()).isEqualTo(1L);
    assertThat(baseAmountSum()).isEqualByComparingTo(BigDecimal.ZERO);
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

  /** The sum of every live posting's frozen {@code base_amount} — zero when everything balances. */
  private BigDecimal baseAmountSum() {
    return jdbcClient
        .sql(
            "select coalesce(sum(p.base_amount), 0) from posting p"
                + " join transaction t on p.transaction_id = t.transaction_id"
                + " where t.deleted_at is null")
        .query(BigDecimal.class)
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
  void categoryResolveSelectsTheLineByIndexWhenSeveralAreSubmitted() throws Exception {
    // Regression (fixed UI issue): a split line's resolve carries every line's categoryText;
    // without
    // the index the values arrived joined ("Food,Fuel") and nothing matched. The index picks line
    // 1.
    insertCategory("Food");
    long fuel = accountService.insertLeaf("Fuel", "expense", null, EUR).accountId();

    mockMvc
        .perform(
            post("/categories/resolve")
                .param("categoryText", "Food", "Fuel")
                .param("index", "1")
                .param("fieldName", "lineCategoryId")
                .param("typeFieldName", "lineCategoryType"))
        .andExpect(status().isOk())
        // The second line ("Fuel") resolves — into the line's own hidden id and type fields.
        .andExpect(content().string(containsString("name=\"lineCategoryId\"")))
        .andExpect(content().string(containsString("value=\"" + fuel + "\"")))
        .andExpect(content().string(containsString("name=\"lineCategoryType\"")));
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
        .andExpect(content().string(containsString("Fuel")))
        // The suggestion also OOB-fills the resolved hidden categoryId (register §3.9), so
        // accepting
        // the ghost as-is is immediately committable — no manual re-trigger of /categories/resolve.
        .andExpect(content().string(containsString("name=\"categoryId\"")))
        .andExpect(content().string(containsString("value=\"" + fuel + "\"")));
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
