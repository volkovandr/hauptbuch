package volkovandr.hauptbuch.categories;

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
 * Integration tier (plan §1.5): the stage-6b categories screen driven through the controller
 * against real Postgres — the stage's acceptance surface. A top-level category is a plain
 * base-currency leaf; naming a posted-to leaf as a new category's parent subdivides it into that
 * child plus an {@code Uncategorized} sibling holding its history.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container — including the
 * base-currency write, which is write-once and would otherwise lock the shared book.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CategoriesScreenIntegrationTest {

  private static final String CATEGORIES_PATH = "/categories";
  private static final String CATEGORY_PATH_PREFIX = "/categories/";
  private static final String NAME = "name";
  private static final String TYPE = "type";
  private static final String PARENT_ID = "parentId";
  private static final String EXPENSE = "expense";
  private static final String EUR = "EUR";
  private static final String FOOD = "Food";
  private static final String MILK = "Milk";
  private static final String GROCERIES = "Groceries";
  private static final String RETURNING_ID = "returning account_id";
  private static final String INSERT_POSTING =
      "insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)";
  private static final String AMT = "amt";
  private static final String CASH = "Cash";
  private static final String ASSET = "asset";
  private static final String CHF = "CHF";
  private static final String TARGET_LEAF_ID = "targetLeafId";

  /**
   * Sum-to-zero, conditional on currency mix (data-model §8 invariant 1) — the detector for a
   * posting re-filed onto a leaf of the wrong currency: its transaction turns cross-currency with
   * no frozen {@code base_amount} on any leg. Counts the offending live transactions.
   */
  private static final String SUM_TO_ZERO_VIOLATIONS =
      """
      with live as (
        select p.transaction_id, p.amount, p.base_amount, a.currency_code
        from posting p
        join transaction t on p.transaction_id = t.transaction_id
        join account a on p.account_id = a.account_id
        where t.deleted_at is null
      ),
      per_txn as (
        select transaction_id,
               count(distinct currency_code)               as currencies,
               sum(amount)                                 as native_sum,
               sum(base_amount)                            as base_sum,
               count(*) filter (where base_amount is null) as missing_base
        from live
        group by transaction_id
      )
      select count(*)
      from per_txn
      where (currencies = 1 and native_sum <> 0)
         or (currencies > 1 and (missing_base > 0 or base_sum <> 0 or base_sum is null))
      """;

  /**
   * Leaves-only for accounts (data-model §8 invariant 2): no posting may reference an account that
   * is some <em>live</em> account's parent. Scoped to live children the way {@code
   * findParentAccountIds} is — a parent whose children were all just deleted is a leaf again.
   */
  private static final String LEAVES_ONLY_VIOLATIONS =
      """
      select count(*)
      from posting p
      where p.account_id in (
        select parent_id from account where parent_id is not null and deleted_at is null
      )
      """;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long accountIdNamed(String name) {
    return jdbcClient
        .sql("select account_id from account where name = :name")
        .param(NAME, name)
        .query(Long.class)
        .single();
  }

  private long insertAccount(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, 'EUR') "
                + RETURNING_ID)
        .param("n", name)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  private long insertChildAccount(String name, String type, long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id) "
                + "values (:n, :t, 'EUR', :p) "
                + RETURNING_ID)
        .param("n", name)
        .param("t", type)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private long insertAccountIn(String name, String type, String currencyCode) {
    return jdbcClient
        .sql("insert into account (name, type, currency_code) values (:n, :t, :c) " + RETURNING_ID)
        .param("n", name)
        .param("t", type)
        .param("c", currencyCode)
        .query(Long.class)
        .single();
  }

  private long insertCurrencyLeafAccount(String currencyCode, String type, long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id, currency_leaf) "
                + "values (:n, :t, :n, :p, true) "
                + RETURNING_ID)
        .param("n", currencyCode)
        .param("t", type)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private void insertPosting(long txnId, long accountId, String amount) {
    jdbcClient
        .sql(INSERT_POSTING)
        .param("t", txnId)
        .param("a", accountId)
        .param(AMT, new BigDecimal(amount))
        .update();
  }

  private long newTransaction() {
    return jdbcClient
        .sql("insert into transaction (date) values ('2026-07-01') returning transaction_id")
        .query(Long.class)
        .single();
  }

  private BigDecimal balanceOf(long accountId) {
    return jdbcClient
        .sql("select coalesce(sum(amount), 0) from posting where account_id = :id")
        .param("id", accountId)
        .query(BigDecimal.class)
        .single();
  }

  private long currencyLeafOf(long categoryId, String currencyCode) {
    return jdbcClient
        .sql(
            "select account_id from account "
                + "where parent_id = :p and currency_code = :c and deleted_at is null")
        .param("p", categoryId)
        .param("c", currencyCode)
        .query(Long.class)
        .single();
  }

  private long childCountOf(long accountId) {
    return jdbcClient
        .sql("select count(*) from account where parent_id = :p and deleted_at is null")
        .param("p", accountId)
        .query(Long.class)
        .single();
  }

  /** The book's two structural invariants, asserted after a deletion has moved postings around. */
  private void assertNoInvariantViolations() {
    assertThat(jdbcClient.sql(SUM_TO_ZERO_VIOLATIONS).query(Long.class).single())
        .as("sum-to-zero violations")
        .isZero();
    assertThat(jdbcClient.sql(LEAVES_ONLY_VIOLATIONS).query(Long.class).single())
        .as("leaves-only violations")
        .isZero();
  }

  @Test
  void screenOffersTheCreateForm() throws Exception {
    mockMvc
        .perform(get(CATEGORIES_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Create a category")));
  }

  @Test
  void topLevelCategoryAppearsInTheList() throws Exception {
    mockMvc
        .perform(post(CATEGORIES_PATH).param(NAME, FOOD).param(TYPE, EXPENSE))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(CATEGORIES_PATH));

    mockMvc.perform(get(CATEGORIES_PATH)).andExpect(content().string(containsString(FOOD)));

    String currency =
        jdbcClient
            .sql("select currency_code from account where account_id = :id")
            .param("id", accountIdNamed(FOOD))
            .query(String.class)
            .single();
    assertThat(currency).isEqualTo(EUR);
  }

  @Test
  void namingPostedLeafAsParentSubdividesIt() throws Exception {
    mockMvc
        .perform(post(CATEGORIES_PATH).param(NAME, FOOD).param(TYPE, EXPENSE))
        .andExpect(status().is3xxRedirection());
    long foodId = accountIdNamed(FOOD);

    // Give Food a posting so the next child triggers subdivision.
    long cash = insertAccount(CASH, ASSET);
    long txn = newTransaction();
    insertPosting(txn, foodId, "5.00");
    insertPosting(txn, cash, "-5.00");

    mockMvc
        .perform(
            post(CATEGORIES_PATH)
                .param(NAME, MILK)
                .param(TYPE, EXPENSE)
                .param(PARENT_ID, String.valueOf(foodId)))
        .andExpect(status().is3xxRedirection());

    mockMvc
        .perform(get(CATEGORIES_PATH))
        .andExpect(content().string(containsString(MILK)))
        .andExpect(content().string(containsString("Uncategorized")));

    List<String> childNames =
        jdbcClient
            .sql("select name from account where parent_id = :id order by name")
            .param("id", foodId)
            .query(String.class)
            .list();
    assertThat(childNames).containsExactly(MILK, "Uncategorized");

    BigDecimal foodBalance =
        jdbcClient
            .sql("select coalesce(sum(amount), 0) from posting where account_id = :id")
            .param("id", foodId)
            .query(BigDecimal.class)
            .single();
    assertThat(foodBalance).isEqualByComparingTo("0");
  }

  @Test
  void creatingChildUnderCategoryWithOnlyCurrencyLeafChildrenGroupsThemUnderUncategorized()
      throws Exception {
    // The plan stage 7d.1 follow-up bug: "Food" was never posted to directly — its EUR and CHF
    // spends already routed onto auto-managed currency leaves (data-model §6.5) — so adding a real
    // child ("Restaurants") must still subdivide, grouping the existing currency leaves under a
    // new Uncategorized, not just insert a plain sibling next to them.
    long food = insertAccount(FOOD, EXPENSE);
    long eurLeaf = insertCurrencyLeafAccount(EUR, EXPENSE, food);
    long chfLeaf = insertCurrencyLeafAccount("CHF", EXPENSE, food);
    long cash = insertAccount(CASH, ASSET);
    long firstSpend = newTransaction();
    insertPosting(firstSpend, eurLeaf, "10.00");
    insertPosting(firstSpend, cash, "-10.00");
    long secondSpend = newTransaction();
    insertPosting(secondSpend, chfLeaf, "8.00");
    insertPosting(secondSpend, cash, "-8.00");

    mockMvc
        .perform(
            post(CATEGORIES_PATH)
                .param(NAME, "Restaurants")
                .param(TYPE, EXPENSE)
                .param(PARENT_ID, String.valueOf(food)))
        .andExpect(status().is3xxRedirection());

    // Food gains exactly "Restaurants" and "Uncategorized" — not a third currency-named sibling.
    List<String> foodChildren =
        jdbcClient
            .sql("select name from account where parent_id = :id order by name")
            .param("id", food)
            .query(String.class)
            .list();
    assertThat(foodChildren).containsExactly("Restaurants", "Uncategorized");

    // The pre-existing currency leaves moved under Uncategorized, keeping their own identity.
    long uncategorizedId = accountIdNamed("Uncategorized");
    List<String> uncategorizedChildren =
        jdbcClient
            .sql("select name from account where parent_id = :id order by name")
            .param("id", uncategorizedId)
            .query(String.class)
            .list();
    assertThat(uncategorizedChildren).containsExactly("CHF", "EUR");

    // Their postings were never touched — only re-parented, not reassigned.
    BigDecimal eurBalance =
        jdbcClient
            .sql("select coalesce(sum(amount), 0) from posting where account_id = :id")
            .param("id", eurLeaf)
            .query(BigDecimal.class)
            .single();
    assertThat(eurBalance).isEqualByComparingTo("10.00");

    mockMvc
        .perform(get(CATEGORIES_PATH))
        .andExpect(content().string(containsString("Restaurants")))
        .andExpect(content().string(containsString("Uncategorized")));
  }

  @Test
  void grandchildCategoryIndentsFurtherThanItsParent() throws Exception {
    long food = insertAccount(FOOD, EXPENSE);
    long sweets = insertChildAccount("Sweets", EXPENSE, food);
    insertChildAccount("M&Ms", EXPENSE, sweets);

    // Sweets (depth 1) and M&Ms (depth 2) must carry different indentation, not the same one.
    mockMvc
        .perform(get(CATEGORIES_PATH))
        .andExpect(content().string(containsString("--depth: 1")))
        .andExpect(content().string(containsString("--depth: 2")));
  }

  @Test
  void deletePanelMovesPostingsAndRemovesTheSubtree() throws Exception {
    long food = insertAccount(FOOD, EXPENSE);
    long milk = insertChildAccount(MILK, EXPENSE, food);
    long cash = insertAccount(CASH, ASSET);
    long txn = newTransaction();
    insertPosting(txn, milk, "4.00");
    insertPosting(txn, cash, "-4.00");
    long groceries = insertAccount(GROCERIES, EXPENSE);

    // The edit page offers Groceries as a move-to target (Milk is inside the subtree, excluded).
    mockMvc
        .perform(get(CATEGORY_PATH_PREFIX + food))
        .andExpect(content().string(containsString("Delete this category")))
        .andExpect(content().string(containsString(GROCERIES)));

    mockMvc
        .perform(
            post(CATEGORY_PATH_PREFIX + food + "/delete")
                .param(TARGET_LEAF_ID, String.valueOf(groceries)))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(CATEGORIES_PATH));

    // The whole subtree is gone from the live list; the postings landed on Groceries.
    List<String> live =
        jdbcClient
            .sql("select name from account where deleted_at is null and type = 'expense'")
            .query(String.class)
            .list();
    assertThat(live).containsExactly(GROCERIES);

    BigDecimal groceriesBalance =
        jdbcClient
            .sql("select coalesce(sum(amount), 0) from posting where account_id = :id")
            .param("id", groceries)
            .query(BigDecimal.class)
            .single();
    assertThat(groceriesBalance).isEqualByComparingTo("4.00");
  }

  @Test
  void deletePanelOffersCategoryWhoseOnlyChildrenAreCurrencyLeaves() throws Exception {
    // The reported case (issue category-management/05): Groceries has been spent in EUR, so it
    // carries a hidden currency leaf. It must still be offered — and accepted — as a target, or a
    // book that uses more than one currency ends up with no offerable target at all.
    long food = insertAccount(FOOD, EXPENSE);
    long cash = insertAccount(CASH, ASSET);
    long txn = newTransaction();
    insertPosting(txn, food, "4.00");
    insertPosting(txn, cash, "-4.00");
    long groceries = insertAccount(GROCERIES, EXPENSE);
    long groceriesEur = insertCurrencyLeafAccount(EUR, EXPENSE, groceries);
    long spent = newTransaction();
    insertPosting(spent, groceriesEur, "7.00");
    insertPosting(spent, cash, "-7.00");

    mockMvc
        .perform(get(CATEGORY_PATH_PREFIX + food))
        .andExpect(content().string(containsString(GROCERIES)));

    mockMvc
        .perform(
            post(CATEGORY_PATH_PREFIX + food + "/delete")
                .param(TARGET_LEAF_ID, String.valueOf(groceries)))
        .andExpect(status().is3xxRedirection());

    // Food's posting joined the existing EUR leaf — not Groceries itself, which is a parent.
    assertThat(balanceOf(groceriesEur)).isEqualByComparingTo("11.00");
    assertThat(balanceOf(groceries)).isEqualByComparingTo("0.00");
    assertNoInvariantViolations();
  }

  @Test
  void deleteFilesEachCurrencysPostingsOnTheTargetsLeafForThatCurrency() throws Exception {
    // A posting is denominated in its account's currency, so moving a CHF posting onto a EUR
    // category would silently reinterpret the amount. Each currency goes to the target's leaf for
    // that same currency, provisioned on first use (data-model §6.5).
    long food = insertAccount(FOOD, EXPENSE);
    long foodEur = insertCurrencyLeafAccount(EUR, EXPENSE, food);
    long foodChf = insertCurrencyLeafAccount(CHF, EXPENSE, food);
    long cashEur = insertAccount(CASH, ASSET);
    long eurTxn = newTransaction();
    insertPosting(eurTxn, foodEur, "4.00");
    insertPosting(eurTxn, cashEur, "-4.00");
    long cashChf = insertAccountIn("Cash CHF", ASSET, CHF);
    long chfTxn = newTransaction();
    insertPosting(chfTxn, foodChf, "9.00");
    insertPosting(chfTxn, cashChf, "-9.00");
    long groceries = insertAccount(GROCERIES, EXPENSE);

    mockMvc
        .perform(
            post(CATEGORY_PATH_PREFIX + food + "/delete")
                .param(TARGET_LEAF_ID, String.valueOf(groceries)))
        .andExpect(status().is3xxRedirection());

    // Groceries was a plain EUR leaf; the CHF postings forced it to subdivide into currency
    // leaves, carrying the EUR ones it had just received into the EUR leaf with it.
    assertThat(balanceOf(currencyLeafOf(groceries, EUR))).isEqualByComparingTo("4.00");
    assertThat(balanceOf(currencyLeafOf(groceries, CHF))).isEqualByComparingTo("9.00");
    assertThat(balanceOf(groceries)).isEqualByComparingTo("0.00");
    assertNoInvariantViolations();
  }

  @Test
  void deleteProvisionsNothingForCurrencyLeavesThatCarryNoPostings() throws Exception {
    // A leaf appears only when it is spent (data-model §6.5): an empty CHF leaf in the subtree
    // must not conjure a CHF leaf on the target.
    long food = insertAccount(FOOD, EXPENSE);
    long foodEur = insertCurrencyLeafAccount(EUR, EXPENSE, food);
    insertCurrencyLeafAccount(CHF, EXPENSE, food);
    long cash = insertAccount(CASH, ASSET);
    long txn = newTransaction();
    insertPosting(txn, foodEur, "4.00");
    insertPosting(txn, cash, "-4.00");
    long groceries = insertAccount(GROCERIES, EXPENSE);

    mockMvc
        .perform(
            post(CATEGORY_PATH_PREFIX + food + "/delete")
                .param(TARGET_LEAF_ID, String.valueOf(groceries)))
        .andExpect(status().is3xxRedirection());

    // Groceries stayed a plain leaf and took the EUR postings directly — no leaves were spawned.
    assertThat(balanceOf(groceries)).isEqualByComparingTo("4.00");
    assertThat(childCountOf(groceries)).isZero();
    assertNoInvariantViolations();
  }

  @Test
  void deleteMovesPostingsToTheDeletedCategorysOwnParent() throws Exception {
    // Deleting M&Ms leaves Sweets childless, so Sweets is a valid target — the postings must land
    // on Sweets itself, not on the doomed child that is still live when the routing runs.
    long sweets = insertAccount("Sweets", EXPENSE);
    long mms = insertChildAccount("M&Ms", EXPENSE, sweets);
    long cash = insertAccount(CASH, ASSET);
    long txn = newTransaction();
    insertPosting(txn, mms, "3.00");
    insertPosting(txn, cash, "-3.00");

    mockMvc
        .perform(
            post(CATEGORY_PATH_PREFIX + mms + "/delete")
                .param(TARGET_LEAF_ID, String.valueOf(sweets)))
        .andExpect(status().is3xxRedirection());

    assertThat(balanceOf(sweets)).isEqualByComparingTo("3.00");
    assertNoInvariantViolations();
  }

  @Test
  void deleteRefusesTargetWithRealSubcategoriesEvenWhenPostedDirectly() {
    // The offer list never shows a genuine group, but the operation validates its own target — the
    // same POST is reachable by hand and, in time, from the MCP surface (CLAUDE.md §1.7).
    long food = insertAccount(FOOD, EXPENSE);
    long cash = insertAccount(CASH, ASSET);
    long txn = newTransaction();
    insertPosting(txn, food, "4.00");
    insertPosting(txn, cash, "-4.00");
    long groceries = insertAccount(GROCERIES, EXPENSE);
    insertChildAccount("Bread", EXPENSE, groceries);

    assertThatThrownBy(
            () ->
                mockMvc.perform(
                    post(CATEGORY_PATH_PREFIX + food + "/delete")
                        .param(TARGET_LEAF_ID, String.valueOf(groceries))))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("subcategories");

    assertThat(accountIdNamed(FOOD)).isEqualTo(food);
  }

  @Test
  void deleteRefusesTargetThatIsNotCategory() {
    long food = insertAccount(FOOD, EXPENSE);
    long cash = insertAccount(CASH, ASSET);
    long txn = newTransaction();
    insertPosting(txn, food, "4.00");
    insertPosting(txn, cash, "-4.00");

    assertThatThrownBy(
            () ->
                mockMvc.perform(
                    post(CATEGORY_PATH_PREFIX + food + "/delete")
                        .param(TARGET_LEAF_ID, String.valueOf(cash))))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(ASSET);

    assertThat(accountIdNamed(FOOD)).isEqualTo(food);
  }

  @Test
  void deletePanelDropsPostinglessCategoryWithNoTarget() throws Exception {
    // The reported case (category-management/06): a just-created category is deleted from the edit
    // screen without being asked for a destination, even though an eligible target exists.
    long food = insertAccount(FOOD, EXPENSE);
    insertAccount(GROCERIES, EXPENSE);

    mockMvc
        .perform(get(CATEGORY_PATH_PREFIX + food))
        .andExpect(content().string(containsString("Delete this category")))
        .andExpect(content().string(containsString("no postings to move")))
        .andExpect(content().string(not(containsString("Move postings to"))));

    mockMvc
        .perform(post(CATEGORY_PATH_PREFIX + food + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(CATEGORIES_PATH));

    List<String> live =
        jdbcClient
            .sql("select name from account where deleted_at is null and type = 'expense'")
            .query(String.class)
            .list();
    assertThat(live).containsExactly(GROCERIES);
  }

  @Test
  void deletePanelDropsPostinglessCategoryEvenWithNoTargetToOffer() throws Exception {
    // The "create one first" refusal must not appear when there is nothing to move: this book has
    // no other expense category at all, and the category still deletes.
    long food = insertAccount(FOOD, EXPENSE);

    mockMvc
        .perform(get(CATEGORY_PATH_PREFIX + food))
        .andExpect(content().string(not(containsString("create one first"))));

    mockMvc
        .perform(post(CATEGORY_PATH_PREFIX + food + "/delete"))
        .andExpect(status().is3xxRedirection());

    assertThat(
            jdbcClient
                .sql("select count(*) from account where deleted_at is null and type = 'expense'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void deletePanelStillDemandsTargetWhenTheSubtreeCarriesPostings() throws Exception {
    long food = insertAccount(FOOD, EXPENSE);
    long milk = insertChildAccount(MILK, EXPENSE, food);
    long cash = insertAccount(CASH, ASSET);
    long txn = newTransaction();
    insertPosting(txn, milk, "4.00");
    insertPosting(txn, cash, "-4.00");
    insertAccount(GROCERIES, EXPENSE);

    mockMvc
        .perform(get(CATEGORY_PATH_PREFIX + food))
        .andExpect(content().string(containsString("Move postings to")));

    // Posting the delete without a target is refused — the postings are never dropped. A plain
    // (non-htmx) form post propagates, so the container's wrapper carries the cause.
    assertThatThrownBy(() -> mockMvc.perform(post(CATEGORY_PATH_PREFIX + food + "/delete")))
        .rootCause()
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("carries postings");

    assertThat(accountIdNamed(FOOD)).isEqualTo(food);
  }

  @Test
  void deletePanelDemandsTargetWhenOnlyPostingsAreVoided() throws Exception {
    // Voided postings still need a home — deleted_at and lifecycle are orthogonal (data-model §5).
    long food = insertAccount(FOOD, EXPENSE);
    long cash = insertAccount(CASH, ASSET);
    long txn = newTransaction();
    insertPosting(txn, food, "4.00");
    insertPosting(txn, cash, "-4.00");
    jdbcClient
        .sql("update transaction set deleted_at = now() where transaction_id = :t")
        .param("t", txn)
        .update();
    insertAccount(GROCERIES, EXPENSE);

    mockMvc
        .perform(get(CATEGORY_PATH_PREFIX + food))
        .andExpect(content().string(containsString("Move postings to")));
  }

  @Test
  void editPageRenamesTheCategory() throws Exception {
    mockMvc
        .perform(post(CATEGORIES_PATH).param(NAME, FOOD).param(TYPE, EXPENSE))
        .andExpect(status().is3xxRedirection());
    long foodId = accountIdNamed(FOOD);

    mockMvc
        .perform(get(CATEGORY_PATH_PREFIX + foodId))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(FOOD)));

    mockMvc
        .perform(post(CATEGORY_PATH_PREFIX + foodId).param(NAME, GROCERIES))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(CATEGORIES_PATH));

    mockMvc.perform(get(CATEGORIES_PATH)).andExpect(content().string(containsString(GROCERIES)));
  }
}
