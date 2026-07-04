package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * SQL-logic tier (plan §1.5): the two recursive-CTE walkers on {@code AccountRepository} — {@link
 * AccountRepository#findLiveByTypesWithDepth} and {@link AccountRepository#findSubtreeAccountIds}.
 * A {@code with recursive} walk of {@code parent_id} to arbitrary depth is logic that lives in the
 * SQL (data-model §5's hierarchy is not two-level), so it is tested here rather than in the
 * integration tier's row-mapping round-trips.
 *
 * <p>This suite boots a Spring context (via {@link TestcontainersConfiguration}) so the query under
 * test is the <em>real</em> repository SQL, not a copy pasted into the test; raw {@link JdbcClient}
 * is used only to seed crafted trees and read back results. {@code @Transactional} rolls each test
 * back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountTreeSqlLogicTest {

  private static final String EUR = "EUR";
  private static final String EXPENSE = "expense";
  private static final String ASSET = "asset";
  private static final String FOOD = "Food";
  private static final String SWEETS = "Sweets";
  private static final String MMS = "M&Ms";
  private static final String MILK = "Milk";

  @Autowired JdbcClient jdbcClient;
  @Autowired AccountRepository accountRepository;

  private long insertRoot(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, :c) "
                + "returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", EUR)
        .query(Long.class)
        .single();
  }

  private long insertChild(String name, String type, long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, parent_id, currency_code) "
                + "values (:n, :t, :p, :c) returning account_id")
        .param("n", name)
        .param("t", type)
        .param("p", parentId)
        .param("c", EUR)
        .query(Long.class)
        .single();
  }

  private void softDelete(long accountId) {
    jdbcClient
        .sql("update account set deleted_at = now() where account_id = :id")
        .param("id", accountId)
        .update();
  }

  // ── findLiveByTypesWithDepth ──────────────────────────────────────────────

  @Test
  void depthWalkGoesArbitrarilyDeepAndOrdersDepthFirst() {
    long food = insertRoot(FOOD, EXPENSE);
    long sweets = insertChild(SWEETS, EXPENSE, food);
    long mms = insertChild(MMS, EXPENSE, sweets);
    long otherSweets = insertChild("Other sweets", EXPENSE, sweets);
    long nonSweets = insertChild("Non-sweets", EXPENSE, food);

    List<AccountNode> nodes = accountRepository.findLiveByTypesWithDepth(List.of(EXPENSE));

    assertThat(nodes)
        .extracting(n -> n.account().accountId(), AccountNode::depth)
        .containsExactly(
            tuple(food, 0),
            tuple(nonSweets, 1),
            tuple(sweets, 1),
            tuple(mms, 2),
            tuple(otherSweets, 2));
  }

  @Test
  void depthWalkPrunesSoftDeletedNodesAndTheirDescendants() {
    long food = insertRoot(FOOD, EXPENSE);
    long sweets = insertChild(SWEETS, EXPENSE, food);
    long mms = insertChild(MMS, EXPENSE, sweets);
    // Soft-delete the middle node: the recursion joins only live rows, so the whole branch
    // beneath it (M&Ms) drops out too, not just the deleted node itself.
    softDelete(sweets);

    List<AccountNode> nodes = accountRepository.findLiveByTypesWithDepth(List.of(EXPENSE));

    assertThat(nodes)
        .extracting(n -> n.account().accountId())
        .contains(food)
        .doesNotContain(sweets, mms);
  }

  @Test
  void depthWalkOrdersSiblingsAlphabeticallyWithinEachLevel() {
    long food = insertRoot(FOOD, EXPENSE);
    long zucchini = insertChild("Zucchini", EXPENSE, food);
    long apple = insertChild("Apple", EXPENSE, food);
    long mango = insertChild("Mango", EXPENSE, food);

    List<AccountNode> nodes = accountRepository.findLiveByTypesWithDepth(List.of(EXPENSE));

    assertThat(nodes)
        .extracting(n -> n.account().accountId())
        .containsExactly(food, apple, mango, zucchini);
  }

  @Test
  void depthWalkKeepsSeparateTypeRootsGrouped() {
    long food = insertRoot(FOOD, EXPENSE);
    long foodChild = insertChild(MILK, EXPENSE, food);
    long cash = insertRoot("Cash", ASSET);
    long cashChild = insertChild("Wallet", ASSET, cash);

    List<AccountNode> expenses = accountRepository.findLiveByTypesWithDepth(List.of(EXPENSE));
    assertThat(expenses)
        .extracting(n -> n.account().accountId())
        .containsExactly(food, foodChild)
        .doesNotContain(cash, cashChild);

    // Asking for both types walks each tree; the query orders by type first, so a tree's nodes
    // stay contiguous rather than interleaving across types.
    List<AccountNode> both = accountRepository.findLiveByTypesWithDepth(List.of(EXPENSE, ASSET));
    assertThat(both)
        .extracting(n -> n.account().accountId())
        .contains(food, foodChild, cash, cashChild);
  }

  @Test
  void depthWalkReturnsEmptyWhenNoAccountOfTypeExists() {
    // 'liability' is a real type with no seeded or crafted rows here.
    assertThat(accountRepository.findLiveByTypesWithDepth(List.of("liability"))).isEmpty();
  }

  // ── findSubtreeAccountIds ─────────────────────────────────────────────────

  @Test
  void subtreeReturnsRootAndEveryDescendantToArbitraryDepth() {
    long food = insertRoot(FOOD, EXPENSE);
    long sweets = insertChild(SWEETS, EXPENSE, food);
    long mms = insertChild(MMS, EXPENSE, sweets);
    long milk = insertChild(MILK, EXPENSE, food);
    long unrelated = insertRoot("Cash", ASSET);

    List<Long> ids = accountRepository.findSubtreeAccountIds(food);

    assertThat(ids).containsExactlyInAnyOrder(food, sweets, mms, milk).doesNotContain(unrelated);
  }

  @Test
  void subtreeExcludesSoftDeletedDescendantsAndTheirChildren() {
    long food = insertRoot(FOOD, EXPENSE);
    long sweets = insertChild(SWEETS, EXPENSE, food);
    long mms = insertChild(MMS, EXPENSE, sweets);
    long milk = insertChild(MILK, EXPENSE, food);
    softDelete(sweets);

    List<Long> ids = accountRepository.findSubtreeAccountIds(food);

    assertThat(ids).containsExactlyInAnyOrder(food, milk).doesNotContain(sweets, mms);
  }

  @Test
  void subtreeOfLeafIsJustThatLeaf() {
    long food = insertRoot(FOOD, EXPENSE);
    long milk = insertChild(MILK, EXPENSE, food);

    assertThat(accountRepository.findSubtreeAccountIds(milk)).containsExactly(milk);
  }

  @Test
  void subtreeOfNonExistentRootIsEmpty() {
    assertThat(accountRepository.findSubtreeAccountIds(-1L)).isEmpty();
  }
}
