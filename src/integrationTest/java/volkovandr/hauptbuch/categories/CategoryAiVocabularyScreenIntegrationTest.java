package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.categories.repository.CategoryAiConfigRepository;

/**
 * Integration tier (plan §1.5): the stage-9d AI-vocabulary editing surface driven through the
 * controller against real Postgres — the "AI parsing" section on the category-edit page (its radios
 * and the spelled-out inherit state), its own POST (upsert, and the delete-on-all-default), and the
 * categories-list deviation annotations. The projection/resolution logic itself is SQL-logic- and
 * unit-tested; this asserts the screen wiring and persistence.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CategoryAiVocabularyScreenIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired CategoryAiConfigRepository configRepository;

  private long insertCategory(String name, String type, Long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, parent_id, currency_code) values (:n, :t, :p, 'EUR')"
                + " returning account_id")
        .param("n", name)
        .param("t", type)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  @Test
  void editPageShowsTheAiParsingSectionSpellingOutTheInheritedDefault() throws Exception {
    long foodId = insertCategory("Food", "expense", null);

    mockMvc
        .perform(get("/categories/{id}", foodId))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("AI parsing"),
                        // Inherit spells out the effective state and its source (the type default).
                        containsString("Inherit — currently: visible (the type default)"))));
  }

  @Test
  void savingAnOverrideUpsertsTheRowAndRedirectsToTheList() throws Exception {
    long foodId = insertCategory("Food", "expense", null);

    mockMvc
        .perform(
            post("/categories/{id}/ai", foodId)
                .param("visible", "false")
                .param("alias", "Groceries")
                .param("aiNote", "M&Ms → for Bobby"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/categories"));

    CategoryAiConfig saved = configRepository.findByAccountId(foodId).orElseThrow();
    assertThat(saved.visible()).isFalse();
    assertThat(saved.alias()).isEqualTo("Groceries");
    assertThat(saved.aiNote()).isEqualTo("M&Ms → for Bobby");
  }

  @Test
  void savingEverythingBackToDefaultDeletesTheRow() throws Exception {
    long foodId = insertCategory("Food", "expense", null);
    configRepository.upsert(foodId, Boolean.FALSE, "Groceries", null);

    mockMvc
        .perform(
            post("/categories/{id}/ai", foodId)
                .param("visible", "inherit")
                .param("alias", "")
                .param("aiNote", ""))
        .andExpect(status().is3xxRedirection());

    assertThat(configRepository.findByAccountId(foodId)).isEmpty();
  }

  @Test
  void categoriesListAnnotatesDeviatingHiddenCategoryButNotDefaultOne() throws Exception {
    long foodId = insertCategory("Food", "expense", null);
    long rentId = insertCategory("Rent", "expense", null);
    configRepository.upsert(foodId, Boolean.FALSE, null, null); // hide Food

    mockMvc
        .perform(get("/categories"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("AI: hidden")));

    // Rent stays on the default (expense = visible), so it carries no annotation of its own — the
    // list stays clean where nothing is curated.
    assertThat(rentId).isNotEqualTo(foodId);
  }

  @Test
  void categoriesListShowsAliasAndNoteForVisibleCategory() throws Exception {
    long foodId = insertCategory("Food", "expense", null); // expense is visible by default
    configRepository.upsert(foodId, null, "Groceries", "diesel → tag Car:Audi");

    mockMvc
        .perform(get("/categories"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("AI alias: Groceries"),
                        containsString("AI note: diesel → tag Car:Audi"),
                        // Visible and on its default → no visibility label.
                        not(containsString("AI: ")))));
  }

  @Test
  void categoriesListHidesAliasAndNoteForHiddenCategory() throws Exception {
    long salaryId = insertCategory("Salary", "income", null); // income is hidden by default
    configRepository.upsert(salaryId, null, "Wages", "some note");

    // A hidden category's alias/note never reach the AI, so they are not surfaced on the list.
    mockMvc
        .perform(get("/categories"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(allOf(not(containsString("AI alias:")), not(containsString("AI note:")))));
  }
}
