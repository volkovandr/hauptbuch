package volkovandr.hauptbuch.shared;

import java.util.List;

/**
 * The names of the reference-data caches (.scratch/performance/issues/02). They live in the shared
 * kernel so a repository in one module can evict a cache that a repository in another module fills
 * — {@code CurrencyRepository} (ledger) evicts the currency list {@code CurrencyOptionRepository}
 * (accounts) reads. {@link CacheConfig} registers exactly {@link #ALL}, so a name missing here
 * fails loudly instead of silently creating an unmanaged cache.
 */
// A constants holder is meant to be nothing but constants.
@SuppressWarnings("PMD.DataClass")
public final class CacheNames {

  /** The single settings row ({@code SettingsRepository.load}; never the AI section). */
  public static final String SETTINGS = "settings";

  /** Every currency, ordered by code (ledger's projection). */
  public static final String CURRENCIES = "currencies";

  /** Every currency as an account-form option (accounts' projection of the same table). */
  public static final String CURRENCY_OPTIONS = "currencyOptions";

  /** Every seeded country; nothing writes it. */
  public static final String COUNTRIES = "countries";

  /** The live account hierarchy per account-type list (categories are accounts too). */
  public static final String ACCOUNT_TREE = "accountTree";

  /** The live tag hierarchy. */
  public static final String TAG_TREE = "tagTree";

  /** The live tags' canonical labels. */
  public static final String TAG_LABELS = "tagLabels";

  /** The live payees. */
  public static final String PAYEES = "payees";

  /** The live payees as filter options. */
  public static final String PAYEE_FILTER_OPTIONS = "payeeFilterOptions";

  /** The live people. */
  public static final String PEOPLE = "people";

  /**
   * The {@code condition} of every {@code @Cacheable}: inside a transaction a read neither uses nor
   * fills the cache. A write in the same transaction has not evicted anything yet (evictions wait
   * for the commit), so a cached read there would miss the transaction's own changes; reading the
   * database sees them. Reads from controllers and pickers run outside transactions and are cached.
   */
  public static final String OUTSIDE_TRANSACTION =
      "!T(org.springframework.transaction.support.TransactionSynchronizationManager)"
          + ".isActualTransactionActive()";

  /** Every cache name, for {@link CacheConfig}. */
  static final List<String> ALL =
      List.of(
          SETTINGS,
          CURRENCIES,
          CURRENCY_OPTIONS,
          COUNTRIES,
          ACCOUNT_TREE,
          TAG_TREE,
          TAG_LABELS,
          PAYEES,
          PAYEE_FILTER_OPTIONS,
          PEOPLE);

  private CacheNames() {}
}
