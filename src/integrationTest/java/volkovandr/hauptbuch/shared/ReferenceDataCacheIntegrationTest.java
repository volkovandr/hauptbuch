package volkovandr.hauptbuch.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;
import volkovandr.hauptbuch.accounts.repository.CurrencyOptionRepository;
import volkovandr.hauptbuch.categories.repository.TagRepository;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.repository.PersonRepository;
import volkovandr.hauptbuch.ledger.Currency;
import volkovandr.hauptbuch.ledger.Settings;
import volkovandr.hauptbuch.ledger.repository.CountryRepository;
import volkovandr.hauptbuch.ledger.repository.CurrencyRepository;
import volkovandr.hauptbuch.ledger.repository.PayeeRepository;
import volkovandr.hauptbuch.ledger.repository.SettingsRepository;
import volkovandr.hauptbuch.ledger.repository.TagReadRepository;

/**
 * Integration tier: the reference-data caches (.scratch/performance/issues/02). Caching is on here
 * and off in every other suite, and the class is deliberately not {@code @Transactional}: evictions
 * run after commit, so a rolled-back test would never see them. Each test therefore commits its
 * rows and {@link #cleanUp()} removes them.
 *
 * <p>A cached read returns the very same list instance, so {@code isSameAs} proves a hit and {@code
 * isNotSameAs} after a write proves the writer evicted it.
 */
// One class on purpose: the point is every cached repository and its writers, side by side.
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.CouplingBetweenObjects"})
@SpringBootTest(properties = "hauptbuch.cache.enabled=true")
@Import(TestcontainersConfiguration.class)
class ReferenceDataCacheIntegrationTest {

  private static final List<String> OWN_TYPES = List.of("asset", "liability");
  private static final String PREFIX = "CacheTest";

  @Autowired JdbcClient jdbcClient;
  @Autowired CacheManager cacheManager;
  @Autowired ApplicationContext applicationContext;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired SettingsRepository settingsRepository;
  @Autowired CurrencyRepository currencyRepository;
  @Autowired CurrencyOptionRepository currencyOptionRepository;
  @Autowired CountryRepository countryRepository;
  @Autowired AccountRepository accountRepository;
  @Autowired TagRepository tagRepository;
  @Autowired TagReadRepository tagReadRepository;
  @Autowired PayeeRepository payeeRepository;
  @Autowired PersonRepository personRepository;

  private String originalDisplayName;
  private boolean settingsRead;

  @AfterEach
  void cleanUp() {
    jdbcClient.sql("delete from account where name like :p").param("p", PREFIX + "%").update();
    jdbcClient.sql("delete from tag where name like :p").param("p", PREFIX + "%").update();
    jdbcClient.sql("delete from payee where name like :p").param("p", PREFIX + "%").update();
    jdbcClient.sql("delete from person where name like :p").param("p", PREFIX + "%").update();
    jdbcClient.sql("delete from currency where currency_code = 'XCT'").update();
    if (settingsRead) {
      jdbcClient
          .sql("update settings set display_name = :n where settings_id = 1")
          .param("n", originalDisplayName)
          .update();
    }
    // The raw deletes above bypass the repositories' evictions, so empty every cache by hand.
    CacheNames.ALL.forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
  }

  /**
   * The stored display name, read around the cache so a stale entry cannot pose as the original.
   */
  private void rememberDisplayName() {
    originalDisplayName =
        jdbcClient
            .sql("select display_name from settings where settings_id = 1")
            .query(String.class)
            .optional()
            .orElse(null);
    settingsRead = true;
  }

  /** The read is cached; running the writer evicts it. */
  private static void assertEvictedBy(Supplier<Object> read, Runnable writer) {
    Object cached = read.get();
    assertThat(read.get()).as("second read is a cache hit").isSameAs(cached);

    writer.run();

    assertThat(read.get()).as("the writer evicted the cache").isNotSameAs(cached);
  }

  private long newAccount(String name) {
    return accountRepository.insert(
        new Account(
            null,
            PREFIX + name,
            "asset",
            null,
            "EUR",
            null,
            null,
            null,
            null,
            false,
            false,
            false));
  }

  // ---- settings ---------------------------------------------------------------------------

  @Test
  void settingsAreCachedAndEveryWriterEvictsThem() {
    rememberDisplayName();
    Settings current = settingsRepository.load();

    List<Runnable> writers =
        List.of(
            () -> settingsRepository.updateBaseCurrency(current.baseCurrency()),
            () -> settingsRepository.updateDisplayName(current.displayName()),
            () -> settingsRepository.updateAiModel(null),
            () -> settingsRepository.updateAiApiKey(null),
            () -> settingsRepository.updateAiSystemPrompt(null),
            () -> settingsRepository.updateStatementSystemPrompt(null),
            () -> settingsRepository.updateAiPrices(null, null, null, null));
    for (Runnable writer : writers) {
      assertEvictedBy(settingsRepository::load, writer);
    }
  }

  @Test
  void writtenDisplayNameIsReadBackAfterTheEviction() {
    rememberDisplayName();
    settingsRepository.load();

    settingsRepository.updateDisplayName(PREFIX + "Owner");

    assertThat(settingsRepository.load().displayName()).isEqualTo(PREFIX + "Owner");
  }

  @Test
  void theAiSettingsHoldingTheApiKeyAreNeverCached() {
    assertThat(settingsRepository.loadAi()).isNotSameAs(settingsRepository.loadAi());
  }

  // ---- currencies and countries -----------------------------------------------------------

  @Test
  void currencyListsAreCachedAndNewCurrencyEvictsBoth() {
    assertEvictedBy(
        currencyRepository::findAll,
        () -> currencyRepository.insert(new Currency("XCT", 2, "¤", PREFIX + " coin")));
    assertThat(currencyRepository.findAll()).extracting(Currency::code).contains("XCT");
    assertThat(currencyOptionRepository.findAll()).anyMatch(o -> "XCT".equals(o.code()));
  }

  @Test
  void currencyOptionsAreEvictedByNewCurrency() {
    assertEvictedBy(
        currencyOptionRepository::findAll,
        () -> currencyRepository.insert(new Currency("XCT", 2, "¤", PREFIX + " coin")));
  }

  @Test
  void countriesAreCachedForGood() {
    assertThat(countryRepository.findAll()).isSameAs(countryRepository.findAll());
  }

  // ---- account tree -----------------------------------------------------------------------

  @Test
  void theAccountTreeIsCachedPerTypeListAndEveryAccountWriterEvictsIt() {
    final long parent = newAccount("Parent");
    final long child = newAccount("Child");
    long other = newAccount("Other");
    Supplier<Object> read = () -> accountRepository.findLiveByTypesWithDepth(OWN_TYPES);

    assertEvictedBy(read, () -> newAccount("Added"));
    assertEvictedBy(read, () -> accountRepository.updateNameAndHue(other, PREFIX + "Renamed", 10));
    assertEvictedBy(read, () -> accountRepository.updateShowOnMainPage(other, true));
    assertEvictedBy(read, () -> accountRepository.updateParent(child, parent));
    assertEvictedBy(read, () -> accountRepository.close(other, java.time.LocalDate.of(2026, 1, 1)));
    assertEvictedBy(read, () -> accountRepository.reopen(other));
    assertEvictedBy(read, () -> accountRepository.updateDetection(other, "1234", false));
    assertEvictedBy(read, () -> accountRepository.softDelete(List.of(other)));
  }

  @Test
  void newAccountAppearsInTheTreeAfterTheEviction() {
    accountRepository.findLiveByTypesWithDepth(OWN_TYPES);

    newAccount("Fresh");

    assertThat(accountRepository.findLiveByTypesWithDepth(OWN_TYPES))
        .anyMatch(n -> (PREFIX + "Fresh").equals(n.account().name()));
  }

  @Test
  void differentTypeListsAreSeparateEntries() {
    newAccount("Typed");
    List<?> assets = accountRepository.findLiveByTypesWithDepth(List.of("asset"));

    assertThat(accountRepository.findLiveByTypesWithDepth(List.of("expense"))).isNotEqualTo(assets);
    assertThat(accountRepository.findLiveByTypesWithDepth(List.of("asset"))).isSameAs(assets);
  }

  // ---- tags -------------------------------------------------------------------------------

  @Test
  void tagTreeAndLabelsAreCachedAndNewTagEvictsBoth() {
    assertEvictedBy(
        tagRepository::findLiveWithDepth, () -> tagRepository.insert(PREFIX + "A", null));
    assertEvictedBy(
        tagReadRepository::liveTagLabels, () -> tagRepository.insert(PREFIX + "B", null));
    assertThat(tagReadRepository.liveTagLabels()).contains(PREFIX + "A", PREFIX + "B");
  }

  // ---- payees -----------------------------------------------------------------------------

  @Test
  void payeeListsAreCachedAndInsertAndRenameEvictThem() {
    long payee = payeeRepository.insert(PREFIX + "Shop", null, null);
    for (Supplier<Object> read :
        List.<Supplier<Object>>of(
            payeeRepository::findAllLive, payeeRepository::findFilterOptions)) {
      assertEvictedBy(read, () -> payeeRepository.insert(PREFIX + "Other", null, null));
      assertEvictedBy(read, () -> payeeRepository.rename(payee, PREFIX + "Renamed"));
    }
  }

  // ---- people -----------------------------------------------------------------------------

  @Test
  void peopleAreCachedAndEveryPersonWriterEvictsThem() {
    Person person = personRepository.insert(PREFIX + "Alice");

    assertEvictedBy(personRepository::findAllLive, () -> personRepository.insert(PREFIX + "Bob"));
    assertEvictedBy(
        personRepository::findAllLive,
        () -> personRepository.updateName(person.personId(), PREFIX + "Alicia"));
    assertEvictedBy(
        personRepository::findAllLive, () -> personRepository.softDelete(person.personId()));
    assertEvictedBy(
        personRepository::findAllLive, () -> personRepository.revive(person.personId()));
  }

  // ---- transactions -----------------------------------------------------------------------

  @Test
  void rolledBackWriteDoesNotEvictAndCommittedOneDoes() {
    List<?> cached = personRepository.findAllLive();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    transaction.executeWithoutResult(
        status -> {
          personRepository.insert(PREFIX + "Rolled");
          status.setRollbackOnly();
        });
    assertThat(personRepository.findAllLive()).as("rolled back: nothing evicted").isSameAs(cached);

    transaction.executeWithoutResult(status -> personRepository.insert(PREFIX + "Committed"));
    assertThat(personRepository.findAllLive()).as("committed: evicted").isNotSameAs(cached);
    assertThat(personRepository.findAllLive())
        .extracting(Person::name)
        .contains(PREFIX + "Committed")
        .doesNotContain(PREFIX + "Rolled");
  }

  @Test
  void readInsideTransactionSeesItsOwnWritesBeforeTheCommit() {
    personRepository.findAllLive();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              personRepository.insert(PREFIX + "Inside");
              assertThat(personRepository.findAllLive())
                  .extracting(Person::name)
                  .contains(PREFIX + "Inside");
            });
  }

  @Test
  void everyCacheNameUsedByRepositoriesIsRegisteredAndEveryRegisteredNameIsUsed() {
    Set<String> used = new HashSet<>();
    for (Object bean : applicationContext.getBeansWithAnnotation(Repository.class).values()) {
      for (Method method : AopUtils.getTargetClass(bean).getDeclaredMethods()) {
        Cacheable cacheable = AnnotatedElementUtils.findMergedAnnotation(method, Cacheable.class);
        CacheEvict evict = AnnotatedElementUtils.findMergedAnnotation(method, CacheEvict.class);
        if (cacheable != null) {
          used.addAll(List.of(cacheable.cacheNames()));
        }
        if (evict != null) {
          used.addAll(List.of(evict.cacheNames()));
        }
      }
    }

    assertThat(used).containsExactlyInAnyOrderElementsOf(CacheNames.ALL);
  }

  @Test
  void cachedListsCannotBeModifiedByCallers() {
    List<?> people = personRepository.findAllLive();

    assertThatThrownBy(() -> people.add(null)).isInstanceOf(UnsupportedOperationException.class);
  }
}
