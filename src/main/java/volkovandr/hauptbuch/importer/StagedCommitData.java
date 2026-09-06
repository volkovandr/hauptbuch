package volkovandr.hauptbuch.importer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.importer.CommitMapsLoader.ResolvedMaps;
import volkovandr.hauptbuch.importer.repository.ImportDuplicateScanRepository;
import volkovandr.hauptbuch.importer.repository.ImportPostingRepository;
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;
import volkovandr.hauptbuch.importer.repository.ImportTransactionRepository;

/**
 * Loads everything the commit (plan f2) iterates over, in a handful of batched reads rather than a
 * query per staged transaction across ~40k rows: the committable transactions and their legs, the
 * staged opening balances, the duplicate-scan {@code skip} decisions, and (via {@link
 * CommitMapsLoader}) the resolved account and category maps. {@link ImportCommitService} then
 * shapes and books each transaction.
 */
@Component
class StagedCommitData {

  private final ImportTransactionRepository importTransactionRepository;
  private final ImportPostingRepository importPostingRepository;
  private final ImportStatisticsRepository importStatisticsRepository;
  private final ImportDuplicateScanRepository importDuplicateScanRepository;
  private final CommitMapsLoader commitMapsLoader;

  StagedCommitData(
      ImportTransactionRepository importTransactionRepository,
      ImportPostingRepository importPostingRepository,
      ImportStatisticsRepository importStatisticsRepository,
      ImportDuplicateScanRepository importDuplicateScanRepository,
      CommitMapsLoader commitMapsLoader) {
    this.importTransactionRepository = importTransactionRepository;
    this.importPostingRepository = importPostingRepository;
    this.importStatisticsRepository = importStatisticsRepository;
    this.importDuplicateScanRepository = importDuplicateScanRepository;
    this.commitMapsLoader = commitMapsLoader;
  }

  /**
   * Everything the commit iterates over, loaded in a handful of batched reads.
   *
   * @param committable the {@code ready}, not-yet-booked staged transactions, date then id order
   *     (opening-balance rows included — routed through the reconciliation, not booked directly)
   * @param legsByTransaction each committable transaction's legs, keyed by {@code
   *     import_transaction_id}
   * @param maps the resolution context for {@link StagedTransactionResolver}
   * @param accountsById every mapped Hauptbuch account, for the opening-balance step's person-leaf
   *     check
   * @param accountMap the account-map rows, for the opening-balance reconciliation outcomes
   * @param stagedOpeningBalances Money's staged opening balances, one per Money account name
   * @param skippedTransactionIds the staged transactions the owner adjudicated {@code skip}
   */
  record CommitData(
      List<ImportTransaction> committable,
      Map<Long, List<ImportPosting>> legsByTransaction,
      StagedTransactionResolver.Maps maps,
      Map<Long, Account> accountsById,
      List<ImportAccount> accountMap,
      List<ImportStagedOpeningBalance> stagedOpeningBalances,
      Set<Long> skippedTransactionIds) {}

  CommitData forSession(long importSessionId) {
    Map<Long, List<ImportPosting>> legsByTransaction =
        importPostingRepository.findBySession(importSessionId).stream()
            .collect(
                Collectors.groupingBy(
                    ImportPosting::importTransactionId, LinkedHashMap::new, Collectors.toList()));
    ResolvedMaps resolved = commitMapsLoader.forSession(importSessionId);

    return new CommitData(
        importTransactionRepository.findCommittableBySession(importSessionId),
        legsByTransaction,
        resolved.maps(),
        resolved.accountsById(),
        resolved.accountMap(),
        importStatisticsRepository.stagedOpeningBalances(importSessionId),
        importDuplicateScanRepository.skippedImportTransactionIds(importSessionId));
  }
}
