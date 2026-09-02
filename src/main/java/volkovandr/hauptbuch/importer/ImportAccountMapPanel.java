package volkovandr.hauptbuch.importer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportFileRepository;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * Assembles the account-map panel of the import review (import.md §5.1, §5.4; plan c1/c2) — the
 * read model {@link ImportReviewService} folds into the review page. Same render-model-assembler
 * shape as {@link ImportReviewService} itself; kept apart from {@link ImportAccountMapService},
 * which owns the domain <em>mutations</em> (mapping a row, toggling {@code expect-file}). This side
 * is a pure projection over {@code import_account}, the staged files' headers, and the account /
 * person / currency option lists a row's forms need.
 *
 * <p>The proposed account type is the first non-null {@code !Type:} header among the files staged
 * for that Money account name (§4.1); null when no file of the account's own is staged yet. A row
 * mapped to a person's leaf (§5.4) is spotted by asking {@code debts} which of the mapped account
 * ids are person leaves — the row then shows the person's name and is tagged {@code personTarget}.
 */
@Service
class ImportAccountMapPanel {

  private final ImportAccountRepository importAccountRepository;
  private final ImportFileRepository importFileRepository;
  private final AccountService accountService;
  private final PersonService personService;
  private final CurrencyService currencyService;

  ImportAccountMapPanel(
      ImportAccountRepository importAccountRepository,
      ImportFileRepository importFileRepository,
      AccountService accountService,
      PersonService personService,
      CurrencyService currencyService) {
    this.importAccountRepository = importAccountRepository;
    this.importFileRepository = importFileRepository;
    this.accountService = accountService;
    this.personService = personService;
    this.currencyService = currencyService;
  }

  ImportAccountMap forSession(long importSessionId) {
    // A file's stated Money account name is only ever the account the file is *for*, never a
    // transfer counterparty — so a name appearing here means that account's own export has been
    // staged ("file provided", §5.1). Tracked apart from proposedTypes because a file may stage
    // with no !Type: header, and "file provided" must not hinge on whether that header parsed.
    Map<String, String> proposedTypes = new HashMap<>();
    Set<String> filedAccountNames = new HashSet<>();
    for (ImportFile file : importFileRepository.findBySession(importSessionId)) {
      filedAccountNames.add(file.moneyAccountName());
      if (file.proposedAccountType() != null) {
        proposedTypes.putIfAbsent(file.moneyAccountName(), file.proposedAccountType());
      }
    }

    List<Account> mappable = accountService.manageableAccounts();
    Map<Long, String> nameById = new HashMap<>();
    for (Account account : mappable) {
      nameById.put(account.accountId(), account.name());
    }

    List<ImportAccount> mapRows = importAccountRepository.findBySession(importSessionId);
    List<Long> mappedAccountIds = new ArrayList<>();
    for (ImportAccount row : mapRows) {
      if (row.accountId() != null) {
        mappedAccountIds.add(row.accountId());
      }
    }
    // debts tells us which of those ids are person leaves (§5.4) — the rest are ordinary accounts.
    Map<Long, String> personLeafNames = personService.personNamesForAccounts(mappedAccountIds);

    List<Person> livePersons = personService.findAllLive();

    List<ImportAccountMap.Row> rows =
        mapRows.stream()
            .map(
                row ->
                    new ImportAccountMap.Row(
                        row.importAccountId(),
                        row.moneyAccountName(),
                        row.accountId(),
                        targetName(row.accountId(), nameById, personLeafNames),
                        row.accountId() != null && personLeafNames.containsKey(row.accountId()),
                        proposedTypes.get(row.moneyAccountName()),
                        row.expectFile(),
                        filedAccountNames.contains(row.moneyAccountName())))
            .toList();

    // manageableAccounts() carries the Accounts-screen order (type, then parent-before-children),
    // which is only legible there with its headers and indentation — in this flat <select> it just
    // reads as shuffled. Sort the options by name, case-insensitive, matching
    // RegisterService.peopleOptions()'s convention for a Java-side name sort.
    List<ImportAccountMap.AccountOption> accountOptions =
        mappable.stream()
            .sorted(Comparator.comparing(Account::name, String.CASE_INSENSITIVE_ORDER))
            .map(a -> new ImportAccountMap.AccountOption(a.accountId(), a.name(), a.type()))
            .toList();
    List<ImportAccountMap.PersonOption> personOptions =
        livePersons.stream()
            .map(p -> new ImportAccountMap.PersonOption(p.personId(), p.name()))
            .toList();
    List<ImportAccountMap.CurrencyOption> currencyOptions =
        currencyService.findAll().stream()
            .map(c -> new ImportAccountMap.CurrencyOption(c.code(), c.name()))
            .toList();

    return new ImportAccountMap(rows, accountOptions, personOptions, currencyOptions);
  }

  private static String targetName(
      Long accountId, Map<Long, String> nameById, Map<Long, String> personLeafNames) {
    if (accountId == null) {
      return null;
    }
    String personName = personLeafNames.get(accountId);
    if (personName != null) {
      return personName;
    }
    return nameById.getOrDefault(accountId, "account #" + accountId);
  }
}
