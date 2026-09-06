package volkovandr.hauptbuch.importer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryTagRepository;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Resolves a campaign's account and category maps into the lookup shape the commit needs (plan f2):
 * the {@link StagedTransactionResolver.Maps} the resolver consumes, plus the mapped {@link
 * Account}s (for the opening-balance step's person-leaf check) and the account-map rows. One
 * batched read per table; each mapped account is looked up once for its currency.
 */
@Component
class CommitMapsLoader {

  private final ImportAccountRepository importAccountRepository;
  private final ImportCategoryRepository importCategoryRepository;
  private final ImportCategoryTagRepository importCategoryTagRepository;
  private final AccountService accountService;
  private final SettingsService settingsService;

  CommitMapsLoader(
      ImportAccountRepository importAccountRepository,
      ImportCategoryRepository importCategoryRepository,
      ImportCategoryTagRepository importCategoryTagRepository,
      AccountService accountService,
      SettingsService settingsService) {
    this.importAccountRepository = importAccountRepository;
    this.importCategoryRepository = importCategoryRepository;
    this.importCategoryTagRepository = importCategoryTagRepository;
    this.accountService = accountService;
    this.settingsService = settingsService;
  }

  /**
   * A campaign's maps in the shape the commit consumes.
   *
   * @param maps the resolution context for {@link StagedTransactionResolver}
   * @param accountsById every mapped Hauptbuch account, keyed by id
   * @param accountMap the account-map rows, for the opening-balance reconciliation outcomes
   */
  record ResolvedMaps(
      StagedTransactionResolver.Maps maps,
      Map<Long, Account> accountsById,
      List<ImportAccount> accountMap) {}

  ResolvedMaps forSession(long importSessionId) {
    List<ImportAccount> accountMap = importAccountRepository.findBySession(importSessionId);
    Map<String, Long> accountIdsByName = new HashMap<>();
    Map<Long, Account> accountsById = new HashMap<>();
    Map<Long, String> currencyByAccountId = new HashMap<>();
    for (ImportAccount row : accountMap) {
      if (row.accountId() == null) {
        continue;
      }
      accountIdsByName.put(row.moneyAccountName(), row.accountId());
      Account account = requireAccount(row.accountId());
      accountsById.put(account.accountId(), account);
      currencyByAccountId.put(account.accountId(), account.currencyCode());
    }

    List<ImportCategory> categories = importCategoryRepository.findBySession(importSessionId);
    Map<Long, List<Long>> tagIdsByCategory =
        importCategoryTagRepository.tagIdsBySession(importSessionId);
    Map<String, Long> categoryIdsByPath = new HashMap<>();
    Map<String, List<Long>> tagIdsByPath = new HashMap<>();
    for (ImportCategory category : categories) {
      if (category.accountId() == null) {
        continue;
      }
      categoryIdsByPath.put(category.moneyPath(), category.accountId());
      tagIdsByPath.put(
          category.moneyPath(),
          List.copyOf(tagIdsByCategory.getOrDefault(category.importCategoryId(), List.of())));
    }

    StagedTransactionResolver.Maps maps =
        new StagedTransactionResolver.Maps(
            baseCurrency(), accountIdsByName, currencyByAccountId, categoryIdsByPath, tagIdsByPath);
    return new ResolvedMaps(maps, accountsById, accountMap);
  }

  private String baseCurrency() {
    return settingsService
        .baseCurrency()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Base currency is not set — no transaction can be booked (data-model §3.8)"));
  }

  private Account requireAccount(long accountId) {
    return accountService
        .findById(accountId)
        .orElseThrow(
            () -> new IllegalStateException("Mapped account " + accountId + " no longer exists"));
  }
}
