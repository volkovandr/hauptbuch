package volkovandr.hauptbuch.importer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportFileRepository;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * The account map (import.md §5.1; plan c1): resolves every Money account name a staged file
 * mentions to a Hauptbuch account — an <strong>existing</strong> one, or a <strong>new</strong> one
 * opened here with the type proposed from the file header and a currency the owner chooses (QIF
 * carries none). The map is many-to-one — several Money names may target one account, which is how
 * a merge and the junk-account cleanup are done.
 *
 * <p>Reads the panel model for the review page and performs the two mapping actions. Person targets
 * and {@code expect-file} (§5.4) land in c2, the opening-balance reconciliation (§5.1) in c3.
 * Nothing here touches the ledger — the campaign still commits atomically at the end (f2) — except
 * that opening a new account is a real {@code accounts} operation, done now so the map has a
 * concrete id to point at.
 */
@Service
public class ImportAccountMapService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportAccountMapService.class);

  /** The account types a Money account may map to — a real account, never a category (§5.1). */
  private static final List<String> MAPPABLE_TYPES = List.of("asset", "liability");

  private final ImportSessionService importSessionService;
  private final ImportAccountRepository importAccountRepository;
  private final ImportFileRepository importFileRepository;
  private final AccountService accountService;
  private final CurrencyService currencyService;

  ImportAccountMapService(
      ImportSessionService importSessionService,
      ImportAccountRepository importAccountRepository,
      ImportFileRepository importFileRepository,
      AccountService accountService,
      CurrencyService currencyService) {
    this.importSessionService = importSessionService;
    this.importAccountRepository = importAccountRepository;
    this.importFileRepository = importFileRepository;
    this.accountService = accountService;
    this.currencyService = currencyService;
  }

  /**
   * The account-map panel for a session: one row per {@code import_account} name with its current
   * target, plus the option lists the row form needs. The proposed type is the first non-null
   * {@code !Type:} header among the files staged for that name (§4.1); null when no file of the
   * account's own has been staged yet.
   */
  public ImportAccountMap mapPanel(long importSessionId) {
    Map<String, String> proposedTypes = new HashMap<>();
    for (ImportFile file : importFileRepository.findBySession(importSessionId)) {
      if (file.proposedAccountType() != null) {
        proposedTypes.putIfAbsent(file.moneyAccountName(), file.proposedAccountType());
      }
    }

    List<Account> mappable = accountService.manageableAccounts();
    Map<Long, String> nameById = new HashMap<>();
    for (Account account : mappable) {
      nameById.put(account.accountId(), account.name());
    }

    List<ImportAccountMap.Row> rows =
        importAccountRepository.findBySession(importSessionId).stream()
            .map(
                row ->
                    new ImportAccountMap.Row(
                        row.importAccountId(),
                        row.moneyAccountName(),
                        row.accountId(),
                        targetName(row.accountId(), nameById),
                        proposedTypes.get(row.moneyAccountName())))
            .toList();

    List<ImportAccountMap.AccountOption> accountOptions =
        mappable.stream()
            .map(a -> new ImportAccountMap.AccountOption(a.accountId(), a.name(), a.type()))
            .toList();
    List<ImportAccountMap.CurrencyOption> currencyOptions =
        currencyService.findAll().stream()
            .map(c -> new ImportAccountMap.CurrencyOption(c.code(), c.name()))
            .toList();

    return new ImportAccountMap(rows, accountOptions, currencyOptions);
  }

  /**
   * Map a Money account name to an existing Hauptbuch account (§5.1). The target must be a live
   * asset or liability account — a category or a per-person leaf is not a mapping target here (a
   * person is c2).
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign, or the account does
   *     not exist or is not mappable
   */
  @Transactional
  public void mapToExisting(long importAccountId, long accountId) {
    ImportAccount row = requireRowInOpenSession(importAccountId);
    Account target =
        accountService
            .findById(accountId)
            .orElseThrow(() -> new IllegalArgumentException("No account with id " + accountId));
    if (!MAPPABLE_TYPES.contains(target.type()) || target.personLeaf() || target.currencyLeaf()) {
      throw new IllegalArgumentException(
          "\"" + row.moneyAccountName() + "\" must map to an asset or liability account");
    }
    importAccountRepository.mapToAccount(importAccountId, accountId, null);
    LOG.info(
        "Import account \"{}\" mapped to account {} ({})",
        row.moneyAccountName(),
        accountId,
        target.name());
  }

  /**
   * Open a new account for a Money account name and map to it (§5.1). Type is {@code asset} or
   * {@code liability} (proposed from the file header, overridable); the currency is required since
   * QIF carries none. The new account has no opening balance — c3 reconciles that.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the row is not in the open campaign, the currency is
   *     missing, or {@link AccountService#openAccount} rejects the draft
   */
  @Transactional
  public void mapToNew(long importAccountId, String name, String type, String currencyCode) {
    ImportAccount row = requireRowInOpenSession(importAccountId);
    if (currencyCode == null || currencyCode.isBlank()) {
      throw new IllegalArgumentException(
          "Choose a currency for the new account \"" + row.moneyAccountName() + "\"");
    }
    if (!currencyService.exists(currencyCode)) {
      // AccountService.openAccount does not validate the currency — an unknown code would surface
      // only as a FK violation (an HTTP 500), so check it here where the message can be friendly.
      throw new IllegalArgumentException("Unknown currency \"" + currencyCode + "\"");
    }
    Account created =
        accountService.openAccount(new AccountDraft(name, type, null, currencyCode, null, null));
    importAccountRepository.mapToAccount(importAccountId, created.accountId(), currencyCode);
    LOG.info(
        "Import account \"{}\" mapped to new account {} ({})",
        row.moneyAccountName(),
        created.accountId(),
        created.name());
  }

  private ImportAccount requireRowInOpenSession(long importAccountId) {
    long sessionId =
        importSessionService
            .currentSession()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No import session is open — start one before mapping accounts."))
            .importSessionId();
    return importAccountRepository.findBySession(sessionId).stream()
        .filter(row -> row.importAccountId() == importAccountId)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No account-map row " + importAccountId + " in the open campaign"));
  }

  private static String targetName(Long accountId, Map<Long, String> nameById) {
    if (accountId == null) {
      return null;
    }
    return Optional.ofNullable(nameById.get(accountId)).orElse("account #" + accountId);
  }
}
