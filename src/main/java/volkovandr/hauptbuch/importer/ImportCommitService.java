package volkovandr.hauptbuch.importer;

import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.importer.ImportReviewService.CommitReadiness;
import volkovandr.hauptbuch.importer.StagedCommitData.CommitData;
import volkovandr.hauptbuch.importer.repository.ImportTransactionRepository;
import volkovandr.hauptbuch.ledger.LedgerService;

/**
 * The import commit (import.md §2, §10; plan f2) — books the whole staged campaign into the ledger
 * in <strong>one atomic database transaction</strong>. Every staged transaction goes through {@link
 * LedgerService#recordTransaction}, the identical validated path every other write uses, so
 * sum-to-zero, cross-currency sum-to-zero, leaves-only and the base-currency gate hold by
 * construction; a failure anywhere leaves the ledger exactly as it was and staging fully intact for
 * another attempt.
 *
 * <p>{@link #commit} is the atomic unit. {@link #clearStaging} runs <em>after</em> it succeeds —
 * staging is cleared once, on success (§2); a failure there leaves only harmless orphan rows under
 * an already-{@code committed} session, which never re-commits.
 *
 * <p>Order inside {@link #commit}: re-run the ledger duplicate scan (§9 — a hand entry may have
 * landed while the review sat open), then refuse unless the <em>whole</em> commit gate is open
 * ({@link ImportReviewService#commitReadiness()} — every account and category mapped, no account
 * still awaiting its own export, no cross-currency transfer still parked, and the scan run with
 * every match adjudicated). A parked transfer is {@code state = 'parked'} and would otherwise be
 * silently skipped and then purged. Then book every {@code ready}, non-opening-balance staged
 * transaction the owner did not adjudicate {@code skip}; apply each account's opening-balance
 * reconciliation ({@link OpeningBalanceCommit}, §5.1); mark the session {@code committed} last, so
 * a cleanup failure can never lead to a re-commit.
 */
@Service
public class ImportCommitService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportCommitService.class);

  private final ImportSessionService importSessionService;
  private final ImportTransactionRepository importTransactionRepository;
  private final ImportDuplicateScanService importDuplicateScanService;
  private final ImportReviewService importReviewService;
  private final ImportStagingPurge importStagingPurge;
  private final StagedCommitData stagedCommitData;
  private final StagedTransactionResolver stagedTransactionResolver;
  private final OpeningBalanceCommit openingBalanceCommit;
  private final LedgerService ledgerService;

  ImportCommitService(
      ImportSessionService importSessionService,
      ImportTransactionRepository importTransactionRepository,
      ImportDuplicateScanService importDuplicateScanService,
      ImportReviewService importReviewService,
      ImportStagingPurge importStagingPurge,
      StagedCommitData stagedCommitData,
      StagedTransactionResolver stagedTransactionResolver,
      OpeningBalanceCommit openingBalanceCommit,
      LedgerService ledgerService) {
    this.importSessionService = importSessionService;
    this.importTransactionRepository = importTransactionRepository;
    this.importDuplicateScanService = importDuplicateScanService;
    this.importReviewService = importReviewService;
    this.importStagingPurge = importStagingPurge;
    this.stagedCommitData = stagedCommitData;
    this.stagedTransactionResolver = stagedTransactionResolver;
    this.openingBalanceCommit = openingBalanceCommit;
    this.ledgerService = ledgerService;
  }

  /**
   * The outcome of a commit.
   *
   * @param booked ordinary staged transactions written to the ledger
   * @param skipped staged transactions the owner adjudicated {@code skip} on the duplicate scan
   * @param openingBalances opening balances brought in or overridden from Money (import.md §5.1)
   */
  public record CommitResult(int booked, int skipped, int openingBalances) {}

  /**
   * The number of staged transactions the commit loop will process (book or skip as a duplicate) —
   * the worker's progress total, which {@link #commit}'s {@code progress} callback counts up to.
   */
  public int committableCount() {
    return importTransactionRepository.countCommittableBySession(requireOpenSession());
  }

  /**
   * Book the whole open campaign in one atomic transaction. {@code progress} is called after each
   * staged transaction is processed (booked, or skipped as an adjudicated duplicate), with the
   * running count — it reaches {@link #committableCount()} on success.
   *
   * @throws IllegalStateException if no campaign is open, the commit gate is not open (an unmapped
   *     or still-parked row, an account still awaiting its file, an un-adjudicated duplicate), or a
   *     staged row cannot be resolved (a missing cross-currency rate) — the whole transaction rolls
   *     back
   */
  @Transactional
  public CommitResult commit(IntConsumer progress) {
    long sessionId = requireOpenSession();

    // The scan re-runs as the commit's first step regardless (§9), so a hand entry made while the
    // review sat open is caught before the gate is checked against it.
    importDuplicateScanService.runScan();
    CommitReadiness gate = importReviewService.commitReadiness();
    if (!gate.ready()) {
      throw new IllegalStateException(
          "The campaign is not ready to commit — resolve the outstanding issues on the review"
              + " first"
              + (gate.scanCleared()
                  ? ""
                  : " (run the ledger duplicate scan and adjudicate every match)")
              + ".");
    }

    CommitData data = stagedCommitData.forSession(sessionId);
    Set<Long> skip = data.skippedTransactionIds();

    int booked = 0;
    int skipped = 0;
    for (ImportTransaction transaction : data.committable()) {
      if (transaction.openingBalance()) {
        continue;
      }
      if (skip.contains(transaction.importTransactionId())) {
        skipped++;
      } else {
        List<ImportPosting> legs =
            data.legsByTransaction().getOrDefault(transaction.importTransactionId(), List.of());
        ledgerService.recordTransaction(
            stagedTransactionResolver.resolve(transaction, legs, data.maps()));
        booked++;
      }
      progress.accept(booked + skipped);
    }

    int openingBalances = openingBalanceCommit.apply(data);

    importSessionService.markCommitted(sessionId);
    LOG.info(
        "Import session {} committed: {} booked, {} skipped, {} opening balance(s)",
        sessionId,
        booked,
        skipped,
        openingBalances);
    return new CommitResult(booked, skipped, openingBalances);
  }

  /**
   * Purge the session's staging (import.md §2 — "staging is cleared once, after a successful
   * commit") via {@link ImportStagingPurge}. Its own transaction, run after {@link #commit}
   * returns.
   */
  public void clearStaging(long importSessionId) {
    importStagingPurge.purge(importSessionId);
  }

  private long requireOpenSession() {
    return importSessionService
        .currentSession()
        .orElseThrow(() -> new IllegalStateException("No import session is open."))
        .importSessionId();
  }
}
