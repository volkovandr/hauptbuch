package volkovandr.hauptbuch.receipts;

import java.util.List;
import java.util.Optional;

/**
 * The batch-parsing seam (ARCH-03, stage 9h): submit many receipts as one Batches-API job at half
 * price, then poll it to completion. Sibling of {@link ReceiptParser}, which stays structurally
 * untouched — single mode did not change shape to accommodate batching. The production
 * implementation ({@code AnthropicReceiptBatchClient}) wraps the official Anthropic Java SDK; tests
 * drive a fake so the suites never touch the network.
 *
 * <p>ARCH-08: like the single-parse seam, the request carries only the documents and the
 * operator-curated parsing instructions — never transactions, balances, or other ledger contents.
 */
public interface ReceiptBatchClient {

  /**
   * Submit a batch and return its API id, which the caller records on every member so a restart (or
   * the poller's next tick) can pick the job back up.
   *
   * @param submission the shared prompt settings and the members
   * @return the batch id
   * @throws ReceiptParseException if the batch could not be created (transport, auth, quota)
   */
  String submit(ReceiptBatchSubmission submission);

  /**
   * Poll a batch: empty while it is still running, present — with one outcome per member — once it
   * has ended.
   *
   * @param batchId the id {@link #submit} returned
   * @param apiKey the resolved API key
   * @return the per-member outcomes, or empty while the batch is still processing
   * @throws ReceiptParseException if the batch could not be polled or has gone (a 404), which the
   *     caller turns into a failure for every member
   */
  Optional<List<ReceiptBatchOutcome>> poll(String batchId, String apiKey);
}
