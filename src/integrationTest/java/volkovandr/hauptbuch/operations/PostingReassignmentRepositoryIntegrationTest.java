package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.operations.repository.PostingReassignmentRepository;

/**
 * Integration tier (plan §1.5): the {@code PostingReassignmentRepository} SQL maps against real
 * Postgres — the two {@code update posting set account_id ...} statements the subdivision and
 * category-deletion operations lean on. Both overloads are exercised: the single-source form
 * (subdivision, plan stage 6b) and the many-source {@code in (:list)} form (category deletion, plan
 * stage 6c), plus the empty-list guard.
 *
 * <p>The operations' <em>decision</em> logic is unit-tested ({@code SubdivisionServiceTest}, {@code
 * DeletionServiceTest}) and driven end to end through the categories screen ({@code
 * CategoriesScreenIntegrationTest}); this test owns the repository's SQL at its own tier.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PostingReassignmentRepositoryIntegrationTest {

  private static final String EUR = "EUR";
  private static final String EXPENSE = "expense";

  @Autowired JdbcClient jdbcClient;
  @Autowired PostingReassignmentRepository postingReassignmentRepository;

  private long insertAccount(String name) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, :c) "
                + "returning account_id")
        .param("n", name)
        .param("t", EXPENSE)
        .param("c", EUR)
        .query(Long.class)
        .single();
  }

  /** Post {@code amount} onto {@code accountId} (the counter-leg is irrelevant to reassignment). */
  private void post(long accountId, String amount) {
    long txnId =
        jdbcClient
            .sql("insert into transaction (date) values ('2026-07-01') returning transaction_id")
            .query(Long.class)
            .single();
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  private BigDecimal balanceOf(long accountId) {
    return jdbcClient
        .sql("select coalesce(sum(amount), 0) from posting where account_id = :id")
        .param("id", accountId)
        .query(BigDecimal.class)
        .single();
  }

  @Test
  void reassignsAllPostingsFromOneAccount() {
    long from = insertAccount("Food");
    long to = insertAccount("Uncategorized");
    post(from, "5.00");
    post(from, "3.00");

    postingReassignmentRepository.reassignPostings(from, to);

    assertThat(balanceOf(from)).isEqualByComparingTo("0");
    assertThat(balanceOf(to)).isEqualByComparingTo("8.00");
  }

  @Test
  void reassignsPostingsFromManyAccountsOntoOneTarget() {
    long milk = insertAccount("Milk");
    long sweets = insertAccount("Sweets");
    long target = insertAccount("Groceries");
    post(milk, "3.00");
    post(sweets, "2.00");

    postingReassignmentRepository.reassignPostings(List.of(milk, sweets), target);

    assertThat(balanceOf(milk)).isEqualByComparingTo("0");
    assertThat(balanceOf(sweets)).isEqualByComparingTo("0");
    assertThat(balanceOf(target)).isEqualByComparingTo("5.00");
  }

  @Test
  void emptySourceListMovesNothing() {
    long target = insertAccount("Groceries");
    post(target, "4.00");

    postingReassignmentRepository.reassignPostings(List.of(), target);

    assertThat(balanceOf(target)).isEqualByComparingTo("4.00");
  }
}
