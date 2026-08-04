package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;

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
 * SQL-logic tier (plan §1.5): {@link AccountRepository#findDetectionCandidates} — the ordering that
 * makes paying-account detection deterministic (data-model §13.4). Labels are deliberately not
 * unique, so "first match wins" only means something because the candidate list arrives in a
 * defined order: transaction-currency matches first, cash accounts last, then by name. That
 * ordering lives entirely in the {@code order by}, which is why this is here rather than in the
 * integration tier's row-mapping round-trips.
 *
 * <p>Boots Spring so the query under test is the real repository SQL; raw {@link JdbcClient} seeds
 * the crafted accounts. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountDetectionCandidatesSqlLogicTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String ASSET = "asset";

  @Autowired JdbcClient jdbcClient;
  @Autowired AccountRepository accountRepository;

  /** The seed (V2) ships its own accounts; the crafted ones are picked out by name. */
  private List<Long> idsOf(List<AccountDetectionCandidate> candidates, List<Long> crafted) {
    return candidates.stream()
        .map(AccountDetectionCandidate::accountId)
        .filter(crafted::contains)
        .toList();
  }

  /** An open, live own account — the shape every candidate has. */
  private long insert(String name, String currency, String labels, boolean cash) {
    return insert(name, ASSET, currency, labels, cash, false, false, false);
  }

  private long insert(
      String name,
      String type,
      String currency,
      String labels,
      boolean cash,
      boolean personLeaf,
      boolean closed,
      boolean deleted) {
    return jdbcClient
        .sql(
            """
            insert into account
              (name, type, currency_code, detection_labels, cash_account, person_leaf,
               closed_at, deleted_at)
            values
              (:n, :t, :c, :l, :cash, :person,
               case when :closed then date '2026-01-01' end,
               case when :deleted then now() end)
            returning account_id
            """)
        .param("n", name)
        .param("t", type)
        .param("c", currency)
        .param("l", labels)
        .param("cash", cash)
        .param("person", personLeaf)
        .param("closed", closed)
        .param("deleted", deleted)
        .query(Long.class)
        .single();
  }

  /**
   * An account that carries a matching label but must never be offered as a paying account: the
   * wrong type, a per-person debt leaf, or closed.
   */
  private long insertExcluded(String name, String type, boolean personLeaf, boolean closed) {
    return insert(name, type, EUR, "1234", false, personLeaf, closed, false);
  }

  @Test
  void accountsInTheTransactionCurrencyComeFirst() {
    long chf = insert("Zebra Card", CHF, "1234", false);
    long eur = insert("Alpha Card", EUR, "1234", false);

    List<Long> ordered = idsOf(accountRepository.findDetectionCandidates(CHF), List.of(chf, eur));

    // CHF wins despite 'Zebra' sorting after 'Alpha' — the currency key outranks the name.
    assertThat(ordered).containsExactly(chf, eur);
  }

  @Test
  void cashAccountsComeLast() {
    long cash = insert("Aaa Cash", EUR, null, true);
    long card = insert("Zzz Card", EUR, "1234", false);

    List<Long> ordered = idsOf(accountRepository.findDetectionCandidates(EUR), List.of(cash, card));

    assertThat(ordered).containsExactly(card, cash);
  }

  @Test
  void namesBreakTheTieAlphabetically() {
    long second = insert("Bank B", EUR, "1234", false);
    long first = insert("Bank A", EUR, "1234", false);
    long thirdCash = insert("Cash A", EUR, null, true);
    long fourthCash = insert("Cash B", EUR, null, true);

    List<Long> ordered =
        idsOf(
            accountRepository.findDetectionCandidates(EUR),
            List.of(second, first, thirdCash, fourthCash));

    // Alphabetical within each tier: the two non-cash accounts, then the two cash ones.
    assertThat(ordered).containsExactly(first, second, thirdCash, fourthCash);
  }

  @Test
  void anUnknownCurrencyLeavesTheCurrencyKeyInert() {
    long cash = insert("Aaa Cash", EUR, null, true);
    long chf = insert("Zzz Card", CHF, "1234", false);
    long eur = insert("Bbb Card", EUR, "1234", false);

    // No currency parsed: the first sort key matches nothing, so cash-last then name decides.
    List<Long> ordered =
        idsOf(accountRepository.findDetectionCandidates(null), List.of(cash, chf, eur));

    assertThat(ordered).containsExactly(eur, chf, cash);
  }

  @Test
  void onlyOpenLiveOwnAccountsAreCandidates() {
    long payable = insertExcluded("Aa Payable", "liability", false, false);
    long person = insertExcluded("Ab personal.EUR", ASSET, true, false);
    long closed = insertExcluded("Ac Closed", ASSET, false, true);
    long deleted = insert("Ad Deleted", ASSET, EUR, "1234", false, false, false, true);
    long category = insertExcluded("Ae Food", "expense", false, false);

    List<AccountDetectionCandidate> candidates = accountRepository.findDetectionCandidates(EUR);

    // A liability (a credit card) pays for receipts; the other four can never be a paying account.
    assertThat(idsOf(candidates, List.of(payable, person, closed, deleted, category)))
        .containsExactly(payable);
  }
}
