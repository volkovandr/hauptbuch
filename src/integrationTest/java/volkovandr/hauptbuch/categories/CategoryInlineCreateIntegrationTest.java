package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (§1.5): the inline-create half of {@code POST /categories/resolve}
 * (receipt-processing/25) — an unknown name is proposed and only an explicit decision creates it,
 * the parent may be named by its full path however deep it sits, and a resolve spends the dock's
 * payee ghost note.
 *
 * <p>Its own class rather than more methods on {@code RegisterEntryScreenIntegrationTest}, where
 * the endpoint's older cases (match by name or path, the refusals, the per-line index) still live:
 * that class is at PMD's class-complexity ceiling, and this flow is a story of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CategoryInlineCreateIntegrationTest {

  private static final String EUR = "EUR";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long insertCategory(String name) {
    return accountService.insertLeaf(name, "expense", null, EUR).accountId();
  }

  private long expenseAccountCount() {
    return jdbcClient
        .sql("select count(*) from account where type = 'expense'")
        .query(Long.class)
        .single();
  }

  @Test
  void categoryResolveProposesTheNewLeafInsteadOfCreatingItSilently() throws Exception {
    insertCategory("Food");
    long expenseAccountsBefore = expenseAccountCount();

    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "Food - Milk"))
        .andExpect(status().isOk())
        // What WOULD be created, and the control that does it — no id yet, so the dock cannot
        // commit an unconfirmed create (receipt-processing/25, folded transaction-register-ui/14).
        .andExpect(content().string(containsString("Milk")))
        .andExpect(content().string(containsString("is new under Food")))
        .andExpect(content().string(containsString("categoryDecision")))
        .andExpect(content().string(containsString("name=\"categoryId\" value=\"\"")));

    assertThat(expenseAccountCount()).isEqualTo(expenseAccountsBefore);
  }

  @Test
  void categoryCreateDecisionCreatesTheProposedLeafAndReportsIt() throws Exception {
    insertCategory("Food");
    long expenseAccountsBefore = expenseAccountCount();

    mockMvc
        .perform(
            post("/categories/resolve")
                .param("categoryText", "Food - Milk")
                .param("categoryDecision", "CREATE"))
        .andExpect(status().isOk())
        // Only the decision post creates: the leaf now exists, its id is carried, and the creation
        // is what the caption reports.
        .andExpect(content().string(containsString("new category: Milk")))
        .andExpect(content().string(containsString("name=\"categoryId\"")))
        .andExpect(content().string(not(containsString("name=\"categoryId\" value=\"\""))));

    assertThat(expenseAccountCount()).isEqualTo(expenseAccountsBefore + 1);
  }

  @Test
  void categoryResolveProposesUnderTheParentNamedByItsFullPath() throws Exception {
    // Owner report 2026-08-30: adding a third level means picking the datalist's "Clothing - Adult"
    // and typing " - Men" after it, so the parent arrives as a PATH. Matching it by bare name alone
    // refused exactly that text, forcing the user to delete the "Clothing - " part by hand.
    long clothing = insertCategory("Clothing");
    long adult = accountService.insertLeaf("Adult", "expense", clothing, EUR).accountId();

    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "Clothing - Adult - Men"))
        .andExpect(status().isOk())
        // The confirm names the parent by its path, so which "Adult" is meant is unmistakable.
        .andExpect(content().string(containsString("is new under Clothing - Adult")))
        .andExpect(content().string(containsString("name=\"categoryId\" value=\"\"")));

    mockMvc
        .perform(
            post("/categories/resolve")
                .param("categoryText", "Clothing - Adult - Men")
                .param("categoryDecision", "CREATE"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("new category: Men")));

    // Created under Adult — the third level, not under Clothing and not at the root.
    assertThat(
            jdbcClient
                .sql("select parent_id from account where name = 'Men'")
                .query(Long.class)
                .single())
        .isEqualTo(adult);
  }

  @Test
  void categoryResolveProposesUnderTheParentThatAlreadyHasChildren() throws Exception {
    // "Clothing - Adult" with a child is no longer a postable leaf, so it never appears in the
    // datalist's leaf paths — adding a second child under it must still resolve.
    long clothing = insertCategory("Clothing");
    long adult = accountService.insertLeaf("Adult", "expense", clothing, EUR).accountId();
    accountService.insertLeaf("Kids", "expense", adult, EUR);

    mockMvc
        .perform(
            post("/categories/resolve")
                .param("categoryText", "Clothing - Adult - Men")
                .param("categoryDecision", "CREATE"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("new category: Men")));

    assertThat(
            jdbcClient
                .sql("select parent_id from account where name = 'Men'")
                .query(Long.class)
                .single())
        .isEqualTo(adult);
  }

  @Test
  void categoryResolveClearsThePayeeGhostNote() throws Exception {
    // The ghost note ("auto · Meat") sits in the next span on the dock's status line, so leaving it
    // there ran it straight into the resolve's own message (owner report 2026-08-30). Resolving the
    // Category by hand spends the suggestion — its OOB-filled id has just been overwritten too.
    insertCategory("Food");

    mockMvc
        .perform(post("/categories/resolve").param("categoryText", "Food"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"entry-ghost\"")))
        .andExpect(content().string(containsString("hx-swap-oob")));
  }

  @Test
  void splitLineResolveDoesNotTouchTheDocksGhostNote() throws Exception {
    // A split line has no ghost slot; an OOB swap aimed at one would just log a missing target.
    insertCategory("Food");

    mockMvc
        .perform(
            post("/categories/resolve")
                .param("categoryText", "Food")
                .param("fieldName", "lineCategoryId"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("entry-ghost"))));
  }
}
