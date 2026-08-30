package volkovandr.hauptbuch.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountService;

/**
 * Integration tier (§1.5): a confirmed inline create that the create itself refuses must come back
 * as the field's own message, not as a 500 (receipt-processing/25).
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}, unlike its sibling screen tests —
 * that is the entire point. {@code CategoryResolutionService.resolveCategory} catches the refusal
 * from {@link CategoryService#createCategory} and returns {@code Refused}; if the resolve ever
 * carried its own transaction, the failed create (a {@code @Transactional} call of its own) would
 * mark that transaction rollback-only and the swallow would surface at commit as an {@code
 * UnexpectedRollbackException} — a 500 the user sees instead of "drop the leading word". A test
 * that rolls itself back never commits, so it can never see that. Hence the manual cleanup below.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CategoryCreateRefusalIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired JdbcClient jdbcClient;

  private long foodId;

  @BeforeEach
  void seedParent() {
    foodId = accountService.insertLeaf("Food", "expense", null, "EUR").accountId();
  }

  @AfterEach
  void removeSeed() {
    jdbcClient.sql("delete from account where parent_id = :id").param("id", foodId).update();
    jdbcClient.sql("delete from account where account_id = :id").param("id", foodId).update();
  }

  @Test
  void confirmedCreateThatTheRuleRefusesReturnsTheMessageNotAnError() throws Exception {
    // "Food - for Kids" proposes the child "for Kids", which createCategory refuses: a name may not
    // begin with a sigil the entry fields parse (ReservedNamePrefix, data-model §7).
    mockMvc
        .perform(
            post("/categories/resolve")
                .param("categoryText", "Food - for Kids")
                .param("categoryDecision", "CREATE"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("cannot begin with")))
        // Nothing resolved, so the dock still cannot commit.
        .andExpect(content().string(containsString("name=\"categoryId\" value=\"\"")));

    assertThat(
            jdbcClient
                .sql("select count(*) from account where parent_id = :id")
                .param("id", foodId)
                .query(Long.class)
                .single())
        .isZero();
  }
}
