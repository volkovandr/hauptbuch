package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.TagReadRepository;

/**
 * SQL-logic tier (plan §1.5): {@link TagReadRepository}'s tag-label reads (register §3.6, plan
 * stage 7e). The load-bearing logic is the recursive CTE that composes a tag's canonical {@code
 * Parent:Child} label root-to-leaf over {@code tag.parent_id} (arbitrary depth, data-model §10.3) —
 * SQL-resident, so it is tested here rather than as a row-mapping round-trip (CLAUDE.md §6).
 *
 * <p>Boots Spring so the query under test is the real repository SQL; raw {@link JdbcClient} only
 * seeds crafted tags/postings. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TagReadSqlLogicTest {

  private static final String EUR = "EUR";

  @Autowired JdbcClient jdbcClient;
  @Autowired TagReadRepository tagReadRepository;

  // ── seeding helpers ───────────────────────────────────────────────────────

  private long insertAccount(String name, String type) {
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

  private long insertTag(String name, Long parentId) {
    return jdbcClient
        .sql("insert into tag (name, parent_id) values (:n, :p) returning tag_id")
        .param("n", name)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private long insertTxn() {
    return jdbcClient
        .sql(
            "insert into transaction (date, lifecycle) values (:d, 'confirmed') "
                + "returning transaction_id")
        .param("d", LocalDate.of(2026, 6, 1))
        .query(Long.class)
        .single();
  }

  private long insertPosting(long txnId, long accountId, String amount) {
    return jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, reconciliation) "
                + "values (:t, :a, :m, 'unreconciled') returning posting_id")
        .param("t", txnId)
        .param("a", accountId)
        .param("m", new java.math.BigDecimal(amount))
        .query(Long.class)
        .single();
  }

  private void tag(long postingId, long tagId) {
    jdbcClient
        .sql("insert into posting_tag (posting_id, tag_id) values (:p, :t)")
        .param("p", postingId)
        .param("t", tagId)
        .update();
  }

  private void softDeleteTag(long tagId) {
    jdbcClient
        .sql("update tag set deleted_at = now() where tag_id = :t")
        .param("t", tagId)
        .update();
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void composesCanonicalHierarchyLabelsForTransactionTags() {
    long car = insertTag("Car", null);
    long passat = insertTag("Passat", car);
    long trip = insertTag("Trip", null);
    // A three-level path proves the recursion is not capped at two.
    long prague = insertTag("Prague", trip);
    long dinner = insertTag("Dinner", prague);

    long cash = insertAccount("Cash", "asset");
    long food = insertAccount("Food", "expense");
    long txn = insertTxn();
    long cashLeg = insertPosting(txn, cash, "-20");
    long foodLeg = insertPosting(txn, food, "20");
    // A transaction-level tag lands on every leg (data-model §10.2); distinct collapses the copies.
    tag(cashLeg, passat);
    tag(foodLeg, passat);
    tag(foodLeg, dinner);

    assertThat(tagReadRepository.tagsForTransaction(txn))
        .extracting(TransactionTag::tagId, TransactionTag::label)
        .containsExactly(tuple(passat, "Car:Passat"), tuple(dinner, "Trip:Prague:Dinner"));
  }

  @Test
  void groupsPostingLabelsByPostingIdAndOmitsUntaggedPostings() {
    long car = insertTag("Car", null);
    long passat = insertTag("Passat", car);
    long fuel = insertTag("Fuel", null);

    long cash = insertAccount("Cash", "asset");
    long food = insertAccount("Food", "expense");
    long txn = insertTxn();
    long cashLeg = insertPosting(txn, cash, "-20");
    long foodLeg = insertPosting(txn, food, "20");
    tag(foodLeg, passat);
    tag(foodLeg, fuel);
    // cashLeg is left untagged — it must be absent from the map, not present-and-empty.

    Map<Long, List<String>> byPosting =
        tagReadRepository.labelsByPosting(List.of(cashLeg, foodLeg));

    assertThat(byPosting).containsOnlyKeys(foodLeg);
    assertThat(byPosting.get(foodLeg)).containsExactly("Car:Passat", "Fuel");
  }

  @Test
  void listsLiveTagLabelsAlphabeticallyAndExcludesDeletedOnes() {
    long car = insertTag("Car", null);
    insertTag("Passat", car);
    long trip = insertTag("Trip", null);
    softDeleteTag(trip);

    // Trip is soft-deleted → not offered; Car and Car:Passat remain, alphabetical.
    assertThat(tagReadRepository.liveTagLabels()).containsExactly("Car", "Car:Passat");
  }

  @Test
  void returnsNoLabelsForAnEmptyPostingList() {
    assertThat(tagReadRepository.labelsByPosting(List.of())).isEmpty();
  }

  @Test
  void labelsTheGivenTagIdsIncludingDeletedOnesAndOmitsUnknownIds() {
    long car = insertTag("Car", null);
    long passat = insertTag("Passat", car);
    long trip = insertTag("Trip", null);
    softDeleteTag(trip);

    // A since-deleted tag still labels (it may already be attached); an unknown id is simply
    // absent.
    Map<Long, String> labels = tagReadRepository.labelsForTagIds(List.of(passat, trip, 9999L));

    assertThat(labels).containsOnly(Map.entry(passat, "Car:Passat"), Map.entry(trip, "Trip"));
  }

  @Test
  void returnsNoLabelsForAnEmptyIdList() {
    assertThat(tagReadRepository.labelsForTagIds(List.of())).isEmpty();
  }
}
