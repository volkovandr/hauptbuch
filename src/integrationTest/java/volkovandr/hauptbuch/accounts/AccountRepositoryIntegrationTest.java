package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;
import volkovandr.hauptbuch.accounts.repository.CurrencyOption;
import volkovandr.hauptbuch.accounts.repository.CurrencyOptionRepository;

/**
 * Integration tier (plan §1.5): the {@code accounts} repositories map rows ↔ records against real
 * Postgres — the account reads the engine leans on (moved here from {@code ledger} at stage 6a),
 * the writes this module now owns, and the read-only currency projection for the account form.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container (plan §15).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountRepositoryIntegrationTest {

  private static final String EUR = "EUR";
  private static final String ASSET = "asset";
  private static final String EXPENSE = "expense";
  private static final String GIRO = "Giro";
  private static final String OPENING_BALANCES = "Opening Balances";
  private static final LocalDate OPENED = LocalDate.of(2026, 7, 1);

  @Autowired JdbcClient jdbcClient;
  @Autowired AccountRepository accountRepository;
  @Autowired CurrencyOptionRepository currencyOptionRepository;

  private Account draft(String name, String type, Integer hue) {
    return new Account(null, name, type, null, EUR, hue, OPENED, null, null, false, false);
  }

  private Account childDraft(String name, String type, long parentId) {
    return new Account(null, name, type, parentId, EUR, null, null, null, null, false, false);
  }

  private Account personLeafDraft(String name) {
    return new Account(null, name, ASSET, null, EUR, null, null, null, null, false, true);
  }

  private Account currencyLeafDraft(String currencyCode, long parentId) {
    return new Account(
        null, currencyCode, EXPENSE, parentId, currencyCode, null, null, null, null, true, false);
  }

  @Test
  void accountRoundTripsIncludingTheSeededSystemLeaf() {
    Optional<Account> openingBalancesEur =
        accountRepository.findLeafUnderParentNamed(OPENING_BALANCES, EUR);
    assertThat(openingBalancesEur).isPresent();
    assertThat(openingBalancesEur.get().type()).isEqualTo("equity");
    assertThat(openingBalancesEur.get().currencyCode()).isEqualTo(EUR);

    Optional<Account> byId = accountRepository.findById(openingBalancesEur.get().accountId());
    assertThat(byId).contains(openingBalancesEur.get());
  }

  @Test
  void parentAccountIdsAreTheNonLeafAccounts() {
    // The seeded system parent (Opening Balances) is a parent of its per-currency leaves.
    List<Long> parentIds = accountRepository.findParentAccountIds();
    List<Long> systemParentIds =
        jdbcClient
            .sql(
                "select account_id from account "
                    + "where name = 'Opening Balances' and parent_id is null")
            .query(Long.class)
            .list();
    assertThat(parentIds).containsAll(systemParentIds);
  }

  @Test
  void parentWhoseOnlyChildrenAreSoftDeletedIsNotaParent() {
    // A parent whose currency leaves have all been soft-deleted is effectively a leaf again — it
    // must be postable (leaves-only, data-model §5). findParentAccountIds counts LIVE children
    // only,
    // matching findChildrenOf; otherwise the ledger rejects a posting the currency-leaf routing
    // deliberately files there (the split-Save 500).
    long parent = accountRepository.insert(draft("Food", EXPENSE, null));
    long child = accountRepository.insert(currencyLeafDraft(EUR, parent));
    jdbcClient
        .sql("update account set deleted_at = now() where account_id = :id")
        .param("id", child)
        .update();

    assertThat(accountRepository.findParentAccountIds()).doesNotContain(parent);
  }

  @Test
  void insertedAccountRoundTripsWithHueAndOpenedDate() {
    long id = accountRepository.insert(draft(GIRO, ASSET, 210));

    Account loaded = accountRepository.findById(id).orElseThrow();
    assertThat(loaded.name()).isEqualTo(GIRO);
    assertThat(loaded.type()).isEqualTo(ASSET);
    assertThat(loaded.currencyCode()).isEqualTo(EUR);
    assertThat(loaded.hue()).isEqualTo(210);
    assertThat(loaded.openedAt()).isEqualTo(OPENED);
    assertThat(loaded.closedAt()).isNull();
    assertThat(loaded.deletedAt()).isNull();
  }

  @Test
  void updateTouchesOnlyNameAndHue() {
    long id = accountRepository.insert(draft(GIRO, ASSET, 210));

    assertThat(accountRepository.updateNameAndHue(id, "Girokonto", 30)).isEqualTo(1);

    Account loaded = accountRepository.findById(id).orElseThrow();
    assertThat(loaded.name()).isEqualTo("Girokonto");
    assertThat(loaded.hue()).isEqualTo(30);
    assertThat(loaded.type()).isEqualTo(ASSET);
    assertThat(loaded.openedAt()).isEqualTo(OPENED);
  }

  @Test
  void closeStampsOnceAndAffectsNoRowsThereafter() {
    long id = accountRepository.insert(draft("Old Giro", ASSET, 210));

    assertThat(accountRepository.close(id, LocalDate.of(2026, 7, 2))).isEqualTo(1);
    assertThat(accountRepository.findById(id).orElseThrow().closedAt())
        .isEqualTo(LocalDate.of(2026, 7, 2));
    // Already closed: the guarded update affects no rows.
    assertThat(accountRepository.close(id, LocalDate.of(2026, 7, 3))).isZero();
  }

  @Test
  void reopenClearsCloseDateOnceAndAffectsNoRowsThereafter() {
    long id = accountRepository.insert(draft("Old Giro", ASSET, 210));
    accountRepository.close(id, LocalDate.of(2026, 7, 2));

    assertThat(accountRepository.reopen(id)).isEqualTo(1);
    assertThat(accountRepository.findById(id).orElseThrow().closedAt()).isNull();
    // Not closed any more: the guarded update affects no rows.
    assertThat(accountRepository.reopen(id)).isZero();
  }

  @Test
  void findLiveByTypesFiltersTypeAndSoftDelete() {
    long giro = accountRepository.insert(draft(GIRO, ASSET, 210));
    long visa = accountRepository.insert(draft("Visa", "liability", 30));
    long deleted = accountRepository.insert(draft("Gone", ASSET, 140));
    jdbcClient
        .sql("update account set deleted_at = now() where account_id = :id")
        .param("id", deleted)
        .update();

    List<Account> live = accountRepository.findLiveByTypes(List.of(ASSET, "liability"));

    assertThat(live).extracting(Account::accountId).contains(giro, visa).doesNotContain(deleted);
    // The seeded equity/income system accounts are not in the managed list.
    assertThat(live).extracting(Account::type).containsOnly(ASSET, "liability");
  }

  @Test
  void postingPresenceIsVisibleToTheLeavesOnlyGuard() {
    long giro = accountRepository.insert(draft(GIRO, ASSET, 210));
    long fresh = accountRepository.insert(draft("Fresh", ASSET, 30));
    long txnId =
        jdbcClient
            .sql("insert into transaction (date) values ('2026-07-01') returning transaction_id")
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount) "
                + "values (:txn, :account, :amount)")
        .param("txn", txnId)
        .param("account", giro)
        .param("amount", new BigDecimal("1.00"))
        .update();

    assertThat(accountRepository.hasPostings(giro)).isTrue();
    assertThat(accountRepository.hasPostings(fresh)).isFalse();
    assertThat(accountRepository.findPostedAccountIds()).contains(giro).doesNotContain(fresh);
  }

  @Test
  void findChildrenOfReturnsLiveChildrenAlphabetically() {
    long parent = accountRepository.insert(draft("Food", EXPENSE, null));
    long milk = accountRepository.insert(childDraft("Milk", EXPENSE, parent));
    long bread = accountRepository.insert(childDraft("Bread", EXPENSE, parent));
    long deleted = accountRepository.insert(childDraft("Gone", EXPENSE, parent));
    jdbcClient
        .sql("update account set deleted_at = now() where account_id = :id")
        .param("id", deleted)
        .update();

    List<Account> children = accountRepository.findChildrenOf(parent);

    assertThat(children).extracting(Account::accountId).containsExactly(bread, milk);
  }

  @Test
  void currencyLeafRoundTripsMarkedAndFindableAmongChildren() {
    long parent = accountRepository.insert(draft("Food", EXPENSE, null));
    long eurLeaf = accountRepository.insert(currencyLeafDraft(EUR, parent));

    Account loaded = accountRepository.findById(eurLeaf).orElseThrow();
    assertThat(loaded.name()).isEqualTo(EUR);
    assertThat(loaded.currencyLeaf()).isTrue();
    assertThat(accountRepository.findChildrenOf(parent))
        .extracting(Account::accountId)
        .containsExactly(eurLeaf);

    // A plain leaf is never marked — the default stays false.
    long milk = accountRepository.insert(childDraft("Milk", EXPENSE, parent));
    assertThat(accountRepository.findById(milk).orElseThrow().currencyLeaf()).isFalse();
  }

  @Test
  void personLeafRoundTripsMarkedAndIndependentOfCurrencyLeaf() {
    long maxEur = accountRepository.insert(personLeafDraft("personal.EUR"));

    Account loaded = accountRepository.findById(maxEur).orElseThrow();
    assertThat(loaded.name()).isEqualTo("personal.EUR");
    assertThat(loaded.type()).isEqualTo(ASSET);
    assertThat(loaded.parentId()).isNull();
    assertThat(loaded.personLeaf()).isTrue();
    assertThat(loaded.currencyLeaf()).isFalse();

    // The two markers are orthogonal — a plain account carries neither (V8's default).
    long cash = accountRepository.insert(draft("Cash", ASSET, 210));
    Account plain = accountRepository.findById(cash).orElseThrow();
    assertThat(plain.personLeaf()).isFalse();
    assertThat(plain.currencyLeaf()).isFalse();
  }

  @Test
  void updateParentMovesAnAccountToItsNewParent() {
    long oldParent = accountRepository.insert(draft("Food", EXPENSE, null));
    long newParent = accountRepository.insert(childDraft("Uncategorized", EXPENSE, oldParent));
    long eurLeaf = accountRepository.insert(currencyLeafDraft(EUR, oldParent));

    assertThat(accountRepository.updateParent(eurLeaf, newParent)).isEqualTo(1);

    assertThat(accountRepository.findById(eurLeaf).orElseThrow().parentId()).isEqualTo(newParent);
    assertThat(accountRepository.findChildrenOf(oldParent))
        .extracting(Account::accountId)
        .containsExactly(newParent);
    assertThat(accountRepository.findChildrenOf(newParent))
        .extracting(Account::accountId)
        .containsExactly(eurLeaf);
  }

  @Test
  void currencyOptionsProjectTheSeededCurrencies() {
    List<CurrencyOption> options = currencyOptionRepository.findAll();
    assertThat(options).extracting(CurrencyOption::code).contains(EUR, "CHF", "JPY").isSorted();
    assertThat(options)
        .filteredOn(o -> EUR.equals(o.code()))
        .singleElement()
        .extracting(CurrencyOption::name)
        .isEqualTo("Euro");
  }
}
