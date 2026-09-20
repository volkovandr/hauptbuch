package volkovandr.hauptbuch.receipts;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.categories.AiVocabularyService;
import volkovandr.hauptbuch.ledger.AiSettings;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The batch worker (stage 9h): the backlog rhythm of receipt doc §3.2 — pre-process many, select
 * them in the register, hit <em>Process</em> once, pay half. Claiming is synchronous so the UI can
 * report immediately how many members it took; the submit itself (building the requests, uploading
 * the images) runs on a background thread, and a {@code @Scheduled} poller finishes the job when
 * the batch ends.
 *
 * <p>Members land through the <em>same</em> lenient seeding path as single mode ({@link
 * ReceiptAnalyser#applyParsed}), so a batch-parsed receipt is indistinguishable downstream from a
 * singly-parsed one — the UI (§3.1) is identical, and 9f/9g need no batch awareness at all.
 *
 * <p><strong>Accepted gap:</strong> a JVM death between the API accepting a batch and the {@code
 * batch_id} write orphans that batch — its members are swept to {@code failed} at boot and the
 * half-price spend is lost. No batch cancel in 9h: soft-deleting a member is the per-receipt
 * escape.
 *
 * <p><strong>Cache warm-up (receipt-processing/31 v3):</strong> a selection of more than one item
 * sends its first item alone as a real 1-item batch and queues the rest behind it — the only kind
 * of cache write a later batch's members reliably read; a standalone synchronous pre-warm call
 * (v1/v2 of this issue) never worked. A queued receipt carries {@code warmupBatchId} instead of
 * {@code batchId} until the poller sees the warm-up batch end and submits it for real.
 */
// DoNotUseThreads: as in 9e, the ratified design is a dedicated single-thread executor — the submit
// must run off the request thread. AvoidCatchingGenericException: the worker's outer guard
// deliberately catches any RuntimeException so an unexpected error still lands the members in
// `failed` rather than leaving them stuck `processing`. CouplingBetweenObjects: this class's whole
// job is orchestrating the batch lifecycle end to end — claim, split/submit (receipt-processing/31
// v3), poll, distribute — so it naturally names every DTO and collaborator along that path (mirrors
// ReportGridBuilder's own justified suppression); splitting it up would just move the same list of
// types onto another class's signature.
@SuppressWarnings({
  "PMD.DoNotUseThreads",
  "PMD.AvoidCatchingGenericException",
  "PMD.CouplingBetweenObjects"
})
@Component
public class ReceiptBatchAnalyser {

  private static final Logger LOG = LoggerFactory.getLogger(ReceiptBatchAnalyser.class);

  /**
   * The Batches API's flat 50 % discount (plan §9h). It covers every token class — cache writes and
   * reads included — so it multiplies the whole computed cost. Anthropic's pricing rule, not an
   * operator-tunable rate: a constant here, not a settings row.
   */
  private static final BigDecimal BATCH_DISCOUNT = new BigDecimal("0.5");

  /** The {@code parse_cost} column's scale ({@code numeric(12,6)}), as in {@code AiSettings}. */
  private static final int COST_SCALE = 6;

  /**
   * How often the poller checks its live batches (plan §9h). A batch takes minutes to hours, so 30
   * s is responsive without being chatty — and the tick costs one indexed query when nothing is in
   * flight, which is the normal state.
   */
  private static final long POLL_INTERVAL_MS = 30_000L;

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "receipt-batch");
            thread.setDaemon(true);
            return thread;
          });

  private final ReceiptService receiptService;
  private final ReceiptBatchClient batchClient;
  private final ReceiptAnalyser receiptAnalyser;
  private final ReceiptAnalysisService analysisService;
  private final ReceiptPromptBuilder promptBuilder;
  private final SettingsService settingsService;
  private final AiVocabularyService aiVocabularyService;
  private final AnthropicProperties properties;

  ReceiptBatchAnalyser(
      ReceiptService receiptService,
      ReceiptBatchClient batchClient,
      ReceiptAnalyser receiptAnalyser,
      ReceiptAnalysisService analysisService,
      ReceiptPromptBuilder promptBuilder,
      SettingsService settingsService,
      AiVocabularyService aiVocabularyService,
      AnthropicProperties properties) {
    this.receiptService = receiptService;
    this.batchClient = batchClient;
    this.receiptAnalyser = receiptAnalyser;
    this.analysisService = analysisService;
    this.promptBuilder = promptBuilder;
    this.settingsService = settingsService;
    this.aiVocabularyService = aiVocabularyService;
    this.properties = properties;
  }

  /**
   * Claim every valid member of a selection (→ {@code processing}) and queue the submit. Members in
   * a state other than {@code pre_processed} are skipped, never blocked — the caller reports the
   * skip count the standard way (§5.2). Returns immediately; nothing is sent on the request thread.
   *
   * @return how many receipts were claimed and will be submitted
   */
  public int start(List<Long> receiptIds) {
    List<Long> claimed = new ArrayList<>();
    for (Long id : receiptIds) {
      if (analysisService.claim(id)) {
        claimed.add(id);
      }
    }
    if (!claimed.isEmpty()) {
      executor.execute(() -> submit(claimed));
    }
    return claimed.size();
  }

  /**
   * Build and send the batch, then stamp its id on every member (package-visible so tests drive it
   * synchronously). A failure anywhere here fails all the claimed members with the reason — the
   * standard Retry path takes it from there.
   *
   * <p>When cache warm-up is enabled (receipt-processing/31 v3) and there is more than one item,
   * only the first is sent now, as its own real 1-item batch — the only kind of cache write a later
   * batch's members reliably read (a standalone synchronous pre-warm call never worked). The rest
   * are queued behind it and released by {@link #releaseWarmupFollowup} once the poller sees that
   * batch end.
   */
  void submit(List<Long> receiptIds) {
    submit(receiptIds, true);
  }

  private void submit(List<Long> receiptIds, boolean allowWarmupSplit) {
    try {
      List<ReceiptBatchItem> items = new ArrayList<>();
      for (Long id : receiptIds) {
        itemFor(id).ifPresent(items::add);
      }
      // A member whose edited image has gone missing cannot be sent; fail it here rather than let
      // it wait out the batch and be swept up as a stranded member with a misleading reason.
      List<Long> sending = items.stream().map(ReceiptBatchItem::receiptId).toList();
      List<Long> missing = receiptIds.stream().filter(id -> !sending.contains(id)).toList();
      analysisService.failClaimed(missing, "The edited image to analyse is missing");
      if (items.isEmpty()) {
        return;
      }

      AiSettings config = settingsService.aiConfig();
      String systemPrompt =
          promptBuilder.build(aiVocabularyService.aiVocabulary(), settingsService.aiSystemPrompt());
      if (allowWarmupSplit && properties.batchCacheWarmupEnabled() && items.size() > 1) {
        submitWithWarmup(items, config, systemPrompt);
      } else {
        sendBatch(items, config, systemPrompt);
      }
    } catch (ReceiptParseException e) {
      LOG.warn("Batch submit failed for {} receipt(s)", receiptIds.size(), e);
      analysisService.failClaimed(receiptIds, e.getMessage());
    } catch (RuntimeException e) {
      LOG.error("Batch submit errored unexpectedly for {} receipt(s)", receiptIds.size(), e);
      analysisService.failClaimed(receiptIds, "Unexpected error: " + e.getMessage());
    }
  }

  /**
   * Send the first item alone as a real 1-item batch to prime the cache, then queue the rest behind
   * it (receipt-processing/31 v3). Non-blocking: returns immediately — the app stays usable while
   * the warm-up batch runs, which can take well over a minute. If the warm-up batch itself cannot
   * be created, there is nothing to wait on, so every item is sent in one ordinary batch instead of
   * stranding the rest.
   */
  private void submitWithWarmup(
      List<ReceiptBatchItem> items, AiSettings config, String systemPrompt) {
    ReceiptBatchItem warmupItem = items.get(0);
    List<ReceiptBatchItem> rest = items.subList(1, items.size());
    String warmupBatchId;
    try {
      warmupBatchId = sendBatch(List.of(warmupItem), config, systemPrompt);
    } catch (ReceiptParseException e) {
      LOG.warn(
          "Batch cache warm-up submit failed; sending {} receipt(s) in one batch instead",
          items.size(),
          e);
      sendBatch(items, config, systemPrompt);
      return;
    }
    List<Long> restIds = rest.stream().map(ReceiptBatchItem::receiptId).toList();
    analysisService.assignWarmupFollowup(restIds, warmupBatchId);
    LOG.info(
        "Batch cache warm-up {} submitted: {} receipt(s) queued behind it",
        warmupBatchId,
        restIds.size());
  }

  /** Build and send one real batch, stamp its id on every member, and log it. */
  private String sendBatch(List<ReceiptBatchItem> items, AiSettings config, String systemPrompt) {
    String batchId =
        batchClient.submit(
            new ReceiptBatchSubmission(
                config.model(),
                config.apiKey(),
                systemPrompt,
                AnthropicPrompts.EDITED_MEDIA_TYPE,
                items));
    List<Long> sending = items.stream().map(ReceiptBatchItem::receiptId).toList();
    analysisService.assignBatch(sending, batchId);
    LOG.info(
        "Batch {} submitted: receipts={} model={} cached=true",
        batchId,
        sending.size(),
        config.model());
    return batchId;
  }

  /**
   * Once a batch has ended — succeeded, failed, or gone — submit whatever is still queued behind it
   * as its own real batch (receipt-processing/31 v3). {@code false} for {@code allowWarmupSplit}:
   * one warm-up item per user-submitted selection, not a chain of them.
   */
  private void releaseWarmupFollowup(String batchId) {
    List<Long> pending = analysisService.warmupFollowupIds(batchId);
    if (!pending.isEmpty()) {
      submit(pending, false);
    }
  }

  /**
   * Poll every batch with a live member, every 30 s (plan §9h). Idle — one cheap query — whenever
   * nothing is in flight, and it resumes naturally on boot: the 9e startup sweep exempts rows that
   * carry a {@code batch_id}, so a batch outlives a restart and is picked back up here.
   */
  @Scheduled(fixedDelay = POLL_INTERVAL_MS)
  public void pollLiveBatches() {
    for (String batchId : analysisService.liveBatchIds()) {
      pollBatch(batchId);
    }
  }

  /**
   * Poll one batch and, if it has ended, distribute its results (package-visible for tests). Either
   * way a batch ends — landed or failed — also releases whatever is queued behind it as a warm-up
   * follow-up (receipt-processing/31 v3); a still-running batch ({@code outcomes} empty) releases
   * nothing yet.
   */
  void pollBatch(String batchId) {
    AiSettings config = settingsService.aiConfig();
    Optional<List<ReceiptBatchOutcome>> outcomes;
    try {
      outcomes = batchClient.poll(batchId, config.apiKey());
    } catch (ReceiptParseException e) {
      LOG.warn("Batch {} is gone; failing its members", batchId, e);
      analysisService.failBatchMembers(batchId, e.getMessage());
      releaseWarmupFollowup(batchId);
      return;
    } catch (RuntimeException e) {
      LOG.error("Batch {} poll errored unexpectedly", batchId, e);
      analysisService.failBatchMembers(batchId, "Unexpected error: " + e.getMessage());
      releaseWarmupFollowup(batchId);
      return;
    }
    outcomes.ifPresent(
        results -> {
          distribute(batchId, results, config);
          releaseWarmupFollowup(batchId);
        });
  }

  /**
   * Land each member: a returned body goes through the 9e seeding path (→ {@code processed}, or
   * {@code failed} when it will not decode), an errored/expired/canceled one straight to {@code
   * failed}. Any member the ended batch returned nothing for is failed rather than left stuck
   * {@code processing}.
   *
   * <p>A result is applied only to a receipt that is <em>still a live {@code processing} member of
   * this batch</em>. Anything else — soft-deleted mid-flight (9e's tolerance: still billed, but the
   * operator meant it), or already moved on — is abandoned rather than written over. Without that
   * guard a late result could land on a receipt someone had since reviewed and, because seeding
   * delete-and-reinserts the draft lines, silently discard their 9f edits.
   */
  private void distribute(String batchId, List<ReceiptBatchOutcome> outcomes, AiSettings config) {
    Set<Long> members = analysisService.batchMemberIds(batchId);
    int succeeded = 0;
    int failed = 0;
    int tokensIn = 0;
    int tokensOut = 0;
    int tokensCacheWrite = 0;
    int tokensCacheRead = 0;
    BigDecimal cost = BigDecimal.ZERO;
    for (ReceiptBatchOutcome outcome : outcomes) {
      long receiptId = outcome.receiptId();
      if (!members.contains(receiptId)) {
        LOG.info(
            "Batch {} result for receipt {} abandoned — no longer a member", batchId, receiptId);
        continue;
      }
      if (outcome.isSucceeded()) {
        ReceiptParseResult result = outcome.result();
        BigDecimal memberCost = costOf(config, result);
        cost = cost.add(memberCost);
        tokensIn += result.tokensIn();
        tokensOut += result.tokensOut();
        tokensCacheWrite += result.tokensCacheWrite();
        tokensCacheRead += result.tokensCacheRead();
        // succeeded/failed here track whether the receipt actually landed processed, not just
        // whether the API returned a body — an undecodable TOON body still leaves the receipt
        // failed even though the call succeeded and was billed.
        boolean processed =
            receiptAnalyser.applyParsed(
                receiptId, result, memberCost, "Could not decode the parser response");
        if (processed) {
          succeeded++;
        } else {
          failed++;
        }
      } else {
        analysisService.failTransport(receiptId, outcome.failure());
        failed++;
      }
    }
    int stranded =
        analysisService.failBatchMembers(batchId, "The batch returned no result for this receipt");
    if (stranded > 0) {
      LOG.warn("Batch {} ended without a result for {} receipt(s)", batchId, stranded);
    }
    LOG.info(
        "Batch {} finished: receipts={} succeeded={} failed={} tokensIn={} tokensOut={} "
            + "tokensCacheWrite={} tokensCacheRead={} cost={}",
        batchId,
        members.size(),
        succeeded,
        failed + stranded,
        tokensIn,
        tokensOut,
        tokensCacheWrite,
        tokensCacheRead,
        cost);
  }

  /** The frozen cost of a batch member: the configured rates, halved by the batch discount. */
  private static BigDecimal costOf(AiSettings config, ReceiptParseResult result) {
    return config
        .costOf(
            result.tokensIn(),
            result.tokensOut(),
            result.tokensCacheWrite(),
            result.tokensCacheRead())
        .multiply(BATCH_DISCOUNT)
        .setScale(COST_SCALE, RoundingMode.HALF_UP);
  }

  /** One member's request material, or empty when its edited image has gone missing. */
  private Optional<ReceiptBatchItem> itemFor(long receiptId) {
    return receiptService
        .findById(receiptId)
        .flatMap(
            receipt ->
                receiptService
                    .editedBytes(receiptId)
                    .map(
                        bytes ->
                            new ReceiptBatchItem(
                                receiptId,
                                promptBuilder.userText(receipt.aiNote()),
                                Base64.getEncoder().encodeToString(bytes))));
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}
