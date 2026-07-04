package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
    long cash = insertAccount("Cash", "asset");
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
    long cash = insertAccount("Cash", "asset");
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
                .param("targetLeafId", String.valueOf(groceries)))
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
