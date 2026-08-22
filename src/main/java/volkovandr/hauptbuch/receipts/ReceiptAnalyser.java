package volkovandr.hauptbuch.receipts;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.categories.AiVocabularyService;
import volkovandr.hauptbuch.ledger.AiSettings;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The single-receipt analyse worker (stage 9e): claims a {@code pre_processed} receipt, greys the
 * pane while the UI polls a status fragment, and on a dedicated single-thread executor calls the
 * Messages API, records the raw body + usage + frozen cost, seeds the draft lines and header, and
 * flips to {@code processed} — or {@code failed} with the reason (data-model §13.1). The network
 * call never runs inside a DB transaction: the writes go through {@link ReceiptAnalysisService}.
 *
 * <p>Startup sweep: on boot, orphaned single-mode {@code processing} rows are failed — their worker
 * thread died with the JVM (batch rows are exempt, 9h). Soft-delete tolerance: a receipt deleted
 * mid-flight is abandoned when its result comes back.
 */
// DoNotUseThreads: the ratified 9e design is a dedicated single-thread executor — the parse must
// run
// off the request thread while the UI polls. AvoidCatchingGenericException: the worker's outer
// guard
// deliberately catches any RuntimeException so an unexpected error still lands the receipt in
// `failed` rather than leaving it stuck `processing` (a swallowed background thread would too).
@SuppressWarnings({"PMD.DoNotUseThreads", "PMD.AvoidCatchingGenericException"})
@Component
public class ReceiptAnalyser {

  private static final Logger LOG = LoggerFactory.getLogger(ReceiptAnalyser.class);

  /**
   * The states {@link #reparse} may re-seed from — never {@code committed} (receipt-processing/19).
   */
  private static final Set<String> RE_SEEDABLE =
      Set.of(ReceiptState.FAILED, ReceiptState.PROCESSED);

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "receipt-analyse");
            thread.setDaemon(true);
            return thread;
          });

  private final ReceiptService receiptService;
  private final ReceiptParser receiptParser;
  private final ReceiptPromptBuilder promptBuilder;
  private final ToonReceiptDecoder decoder;
  private final ReceiptSeeder seeder;
  private final ReceiptAnalysisService analysisService;
  private final SettingsService settingsService;
  private final AiVocabularyService aiVocabularyService;

  ReceiptAnalyser(
      ReceiptService receiptService,
      ReceiptParser receiptParser,
      ReceiptPromptBuilder promptBuilder,
      ToonReceiptDecoder decoder,
      ReceiptSeeder seeder,
      ReceiptAnalysisService analysisService,
      SettingsService settingsService,
      AiVocabularyService aiVocabularyService) {
    this.receiptService = receiptService;
    this.receiptParser = receiptParser;
    this.promptBuilder = promptBuilder;
    this.decoder = decoder;
    this.seeder = seeder;
    this.analysisService = analysisService;
    this.settingsService = settingsService;
    this.aiVocabularyService = aiVocabularyService;
  }

  /**
   * Begin analysing a receipt: atomically claim it (→ {@code processing}) and, only if the claim
   * succeeded, hand the parse to the background thread. A receipt that is not a live {@code
   * pre_processed} one is not claimed and nothing is submitted.
   *
   * @param cachePrompt whether to mark the system prompt with a cache breakpoint — the operator's
   *     "Analyse (cached)" button (9h); worth it only when more parses follow within the 5-minute
   *     TTL, which is why it is a second button rather than the default
   * @return true if the receipt was claimed and queued
   */
  public boolean start(long receiptId, boolean cachePrompt) {
    if (!analysisService.claim(receiptId)) {
      return false;
    }
    executor.execute(() -> run(receiptId, cachePrompt));
    return true;
  }

  /**
   * The parse itself (runs on the worker thread; package-visible so tests drive it synchronously).
   * Abandons a receipt deleted mid-flight, fails a transport error or an undecodable body, and
   * otherwise seeds and processes it. Any unexpected error still lands the receipt in {@code
   * failed} rather than leaving it stuck {@code processing}.
   */
  void run(long receiptId, boolean cachePrompt) {
    Optional<Receipt> found = receiptService.findById(receiptId);
    if (found.isEmpty()) {
      return; // deleted between claim and run — abandon
    }
    Receipt receipt = found.get();
    Optional<byte[]> imageBytes = receiptService.editedBytes(receiptId);
    if (imageBytes.isEmpty()) {
      analysisService.failTransport(receiptId, "The edited image to analyse is missing");
      return;
    }
    try {
      parseAndSeed(receipt, imageBytes.get(), cachePrompt);
    } catch (ReceiptParseException e) {
      LOG.warn("Receipt {} parse failed", receiptId, e);
      analysisService.failTransport(receiptId, e.getMessage());
    } catch (RuntimeException e) {
      LOG.error("Receipt {} analyse errored unexpectedly", receiptId, e);
      analysisService.failTransport(receiptId, "Unexpected error: " + e.getMessage());
    }
  }

  /**
   * Re-seed a {@code failed} or {@code processed} receipt from an operator-edited parser body,
   * <em>without</em> calling the API again (owner feedback 2026-08-02; widened to {@code processed}
   * by issue tracker receipt-processing/19). A model that emitted slightly-malformed TOON (an
   * unquoted comma, say) is far cheaper to fix by hand than to re-prompt: the operator edits the
   * stored raw text, and this decodes + seeds it exactly as a fresh parse would, keeping the
   * already billed token counts and frozen cost. The same path repairs a response that parsed fine
   * but referenced a tag/category/person that did not exist yet — seeding resolves the taxonomy as
   * it stands now, so the stored response applies once it does.
   *
   * <p>Decodes → {@code processed} with the draft lines rebuilt (any manual edits to them are
   * replaced); still undecodable → {@code failed} with the edited text kept for another pass, which
   * is how a bad edit to a {@code processed} receipt lands in the {@code failed} repair surface.
   * Any other state is left untouched — {@code committed} above all, whose lines back a real
   * transaction.
   *
   * @return true if the edited body decoded and the receipt is now {@code processed}
   */
  public boolean reparse(long receiptId, String editedRaw) {
    if (editedRaw == null || editedRaw.isBlank()) {
      return false; // an empty submit would replace the only copy of the response with nothing
    }
    Optional<Receipt> found = receiptService.findById(receiptId);
    if (found.isEmpty() || !isReSeedable(found.get())) {
      return false;
    }
    Receipt receipt = found.get();
    ReceiptParseResult result =
        new ReceiptParseResult(
            editedRaw,
            orZero(receipt.tokensIn()),
            orZero(receipt.tokensOut()),
            orZero(receipt.tokensCacheWrite()),
            orZero(receipt.tokensCacheRead()));
    return applyParsedAndLog(
        receiptId, result, receipt.parseCost(), "Could not decode the edited response");
  }

  /**
   * Whether a receipt may be re-seeded from an edited body: a live {@code failed} or {@code
   * processed} one whose draft lines back no transaction. {@code committed} is excluded outright,
   * and so is a <em>reopened</em> receipt — it is {@code processed} again, but its booked
   * transaction still stands and stays linked, and an undecodable edit would strand it in {@code
   * failed}, whose view offers neither the transaction link nor the transaction-aware delete rung.
   */
  private static boolean isReSeedable(Receipt receipt) {
    return RE_SEEDABLE.contains(receipt.state()) && receipt.transactionId() == null;
  }

  private static int orZero(Integer value) {
    return value == null ? 0 : value;
  }

  /**
   * Decode a returned body and land the receipt: seeded and {@code processed} when it decodes,
   * {@code failed} with the raw body kept when it does not. The single-mode worker, the operator's
   * re-parse, and the 9h batch poller all finish here, so "lenient seeding" means one thing in the
   * app rather than three.
   *
   * @return true if the body decoded and the receipt is now {@code processed}
   */
  boolean applyParsed(
      long receiptId, ReceiptParseResult result, BigDecimal cost, String undecodableReason) {
    Optional<ParsedReceipt> decoded = decoder.decode(result.rawToon());
    if (decoded.isEmpty()) {
      analysisService.failUndecodable(receiptId, undecodableReason, result, cost);
      return false;
    }
    analysisService.applyProcessed(receiptId, result, cost, seeder.seed(decoded.get()));
    return true;
  }

  private void parseAndSeed(Receipt receipt, byte[] imageBytes, boolean cachePrompt) {
    AiSettings config = settingsService.aiConfig();
    ReceiptParseRequest request =
        new ReceiptParseRequest(
            config.model(),
            config.apiKey(),
            promptBuilder.build(
                aiVocabularyService.aiVocabulary(), settingsService.aiSystemPrompt()),
            promptBuilder.userText(receipt.aiNote()),
            AnthropicPrompts.EDITED_MEDIA_TYPE,
            cachePrompt);

    ReceiptParseResult result = receiptParser.parse(request, imageBytes);
    BigDecimal cost =
        config.costOf(
            result.tokensIn(),
            result.tokensOut(),
            result.tokensCacheWrite(),
            result.tokensCacheRead());

    applyParsedAndLog(receipt.receiptId(), result, cost, "Could not decode the parser response");
  }

  /**
   * {@link #applyParsed} plus the "processing finished" INFO line on success — the shape both
   * single-item callers need (run's fresh parse and reparse's re-seed), never the batch poller's
   * per-member call into {@link #applyParsed} directly.
   */
  private boolean applyParsedAndLog(
      long receiptId, ReceiptParseResult result, BigDecimal cost, String undecodableReason) {
    boolean processed = applyParsed(receiptId, result, cost, undecodableReason);
    if (processed) {
      LOG.info(
          "Receipt {} processed: tokensIn={} tokensOut={} tokensCacheWrite={} tokensCacheRead={} "
              + "cost={}",
          receiptId,
          result.tokensIn(),
          result.tokensOut(),
          result.tokensCacheWrite(),
          result.tokensCacheRead(),
          cost);
    }
    return processed;
  }

  /** On boot, fail orphaned single-mode {@code processing} rows (data-model §13.1). */
  @EventListener(ApplicationReadyEvent.class)
  public void sweepOrphansOnStartup() {
    int swept = analysisService.sweepOrphans("Interrupted by a restart while processing");
    if (swept > 0) {
      LOG.info("Startup sweep failed {} orphaned processing receipt(s)", swept);
    }
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}
