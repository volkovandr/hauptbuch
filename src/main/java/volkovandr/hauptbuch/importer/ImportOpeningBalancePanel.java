package volkovandr.hauptbuch.importer;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Assembles the opening-balance reconciliation cells of the account-map panel (import.md §5.1; plan
 * c3), keyed by {@code import_account} id. Split out of {@link ImportAccountMapPanel} so that panel
 * — already at its coupling budget — does not also have to know about the ledger and the proposal
 * rule. {@link ImportReviewService} folds the result onto the review beside the map.
 *
 * <p>For each map row it pairs Money's staged opening balance (the funding leg of the staged
 * opening-balance transaction) with the mapped Hauptbuch account's own (via {@link
 * LedgerService#openingBalanceOf}) and proposes a winner ({@link OpeningBalanceReconciliation});
 * the owner overrides. A row that maps to a person's leaf, or is not mapped, has no Hauptbuch side.
 */
@Service
class ImportOpeningBalancePanel {

  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final ImportAccountRepository importAccountRepository;
  private final ImportStatisticsRepository importStatisticsRepository;
  private final PersonService personService;
  private final LedgerService ledgerService;

  ImportOpeningBalancePanel(
      ImportAccountRepository importAccountRepository,
      ImportStatisticsRepository importStatisticsRepository,
      PersonService personService,
      LedgerService ledgerService) {
    this.importAccountRepository = importAccountRepository;
    this.importStatisticsRepository = importStatisticsRepository;
    this.personService = personService;
    this.ledgerService = ledgerService;
  }

  Map<Long, ImportOpeningBalanceCells> forSession(long importSessionId) {
    List<ImportAccount> mapRows = importAccountRepository.findBySession(importSessionId);

    List<Long> mappedAccountIds =
        mapRows.stream().map(ImportAccount::accountId).filter(id -> id != null).toList();
    Map<Long, String> personLeafNames = personService.personNamesForAccounts(mappedAccountIds);

    // The staged list is ordered by name then date, so the first sighting of a name is the
    // earliest — a rare two-file account (§5.1).
    Map<String, ImportStagedOpeningBalance> staged = new HashMap<>();
    for (ImportStagedOpeningBalance row :
        importStatisticsRepository.stagedOpeningBalances(importSessionId)) {
      staged.putIfAbsent(row.moneyAccountName(), row);
    }

    Map<Long, ImportOpeningBalanceCells> byRow = new HashMap<>();
    for (ImportAccount row : mapRows) {
      byRow.put(
          row.importAccountId(),
          cellsFor(row, staged.get(row.moneyAccountName()), personLeafNames));
    }
    return byRow;
  }

  private ImportOpeningBalanceCells cellsFor(
      ImportAccount row, ImportStagedOpeningBalance money, Map<Long, String> personLeafNames) {
    OpeningBalanceReconciliation.Balance moneyBalance =
        money == null
            ? null
            : new OpeningBalanceReconciliation.Balance(money.date(), money.amount());

    OpeningBalanceReconciliation.Balance hauptbuchBalance = null;
    if (row.accountId() != null && !personLeafNames.containsKey(row.accountId())) {
      hauptbuchBalance =
          ledgerService
              .openingBalanceOf(row.accountId())
              .map(view -> new OpeningBalanceReconciliation.Balance(view.date(), view.amount()))
              .orElse(null);
    }

    return new ImportOpeningBalanceCells(
        format(moneyBalance),
        format(hauptbuchBalance),
        OpeningBalanceReconciliation.propose(hauptbuchBalance, moneyBalance),
        row.openingBalanceChoice(),
        row.openingBalanceAmount() == null
            ? null
            : MoneyFormat.number(row.openingBalanceAmount(), 2));
  }

  private static String format(OpeningBalanceReconciliation.Balance balance) {
    if (balance == null || balance.amount() == null) {
      return null;
    }
    return MoneyFormat.number(balance.amount(), 2) + " · " + GERMAN_DATE.format(balance.date());
  }
}
