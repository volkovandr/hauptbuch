package volkovandr.hauptbuch.receipts;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchErroredResult;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The production {@link ReceiptBatchClient} (stage 9h): the Batches API via the official Anthropic
 * Java SDK. As thin as its single-parse sibling — build the create call, read the processing
 * status, map each result to an outcome — so all judgement (decoding, seeding, cost, state
 * transitions) stays in unit-tested collaborators.
 *
 * <p>Every member's system block carries a cache breakpoint: a batch is precisely the case where
 * the shared prefix is read many times inside the TTL, so the write pays for itself. The 50 % batch
 * discount is applied by the caller when it freezes each member's {@code parse_cost} — it is
 * Anthropic's pricing rule, not an operator-tunable rate, so it lives as a constant rather than a
 * setting.
 */
@Component
class AnthropicReceiptBatchClient implements ReceiptBatchClient {

  private static final Logger LOG = LoggerFactory.getLogger(AnthropicReceiptBatchClient.class);

  private final AnthropicClients clients;

  AnthropicReceiptBatchClient(AnthropicClients clients) {
    this.clients = clients;
  }

  @Override
  public String submit(ReceiptBatchSubmission submission) {
    BatchCreateParams.Builder params = BatchCreateParams.builder();
    for (ReceiptBatchItem item : submission.items()) {
      params.addRequest(
          BatchCreateParams.Request.builder()
              .customId(String.valueOf(item.receiptId()))
              .params(
                  BatchCreateParams.Request.Params.builder()
                      .model(submission.model())
                      .maxTokens(AnthropicPrompts.MAX_TOKENS)
                      .systemOfTextBlockParams(
                          AnthropicPrompts.systemBlocks(submission.systemPrompt(), true))
                      .addUserMessageOfBlockParams(
                          AnthropicPrompts.userBlocks(
                              item.imageBase64(), submission.mediaType(), item.userText()))
                      .build())
              .build());
    }
    try {
      return batches(submission.apiKey()).create(params.build()).id();
    } catch (AnthropicException e) {
      throw new ReceiptParseException("Batch submit failed: " + e.getMessage(), e);
    }
  }

  @Override
  public Optional<List<ReceiptBatchOutcome>> poll(String batchId, String apiKey) {
    try {
      MessageBatch batch = batches(apiKey).retrieve(batchId);
      if (!MessageBatch.ProcessingStatus.ENDED.equals(batch.processingStatus())) {
        return Optional.empty(); // still in progress (or canceling) — poll again next tick
      }
      return Optional.of(collectResults(batchId, apiKey));
    } catch (NotFoundException e) {
      // The batch is gone — nothing will ever come back, so the caller fails every member (§9h).
      throw new ReceiptParseException("Batch poll failed: " + e.getMessage(), e);
    } catch (AnthropicException e) {
      // Anything else — rate limit, 5xx, a network blip — says nothing about the batch, which is
      // still running on Anthropic's side. Failing its members here would throw away the whole
      // half-price job over one bad tick, so treat it as "not ended yet" and poll again in 30 s.
      LOG.warn("Batch {} poll attempt failed; will retry", batchId, e);
      return Optional.empty();
    }
  }

  /** Drain the results stream into one outcome per member; the order is the API's, not ours. */
  private List<ReceiptBatchOutcome> collectResults(String batchId, String apiKey) {
    List<ReceiptBatchOutcome> outcomes = new ArrayList<>();
    try (StreamResponse<MessageBatchIndividualResponse> results =
        batches(apiKey).resultsStreaming(batchId)) {
      results.stream().forEach(response -> outcomeOf(response).ifPresent(outcomes::add));
    }
    return outcomes;
  }

  /**
   * Map one member's result home. The {@code custom_id} is the receipt id we submitted; anything
   * else is not ours to apply, so it is dropped rather than guessed at.
   */
  private static Optional<ReceiptBatchOutcome> outcomeOf(MessageBatchIndividualResponse response) {
    long receiptId;
    try {
      receiptId = Long.parseLong(response.customId());
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    MessageBatchResult result = response.result();
    if (result.isSucceeded()) {
      return Optional.of(
          ReceiptBatchOutcome.succeeded(
              receiptId, AnthropicPrompts.resultOf(result.asSucceeded().message())));
    }
    if (result.isErrored()) {
      return Optional.of(
          ReceiptBatchOutcome.failed(
              receiptId, "The batch request errored: " + errorText(result.asErrored())));
    }
    if (result.isExpired()) {
      return Optional.of(
          ReceiptBatchOutcome.failed(
              receiptId, "The batch expired before this receipt was parsed"));
    }
    return Optional.of(ReceiptBatchOutcome.failed(receiptId, "The batch was canceled"));
  }

  /**
   * The error as the API stated it. {@code ErrorObject} is a nine-way union with no common message
   * accessor, so the wire JSON it was deserialised from is both the most faithful and the most
   * stable thing to show — {@code {"type":"rate_limit_error","message":"…"}} tells the operator
   * exactly what happened, and it does not shift when the SDK changes a Java {@code toString}.
   */
  private static String errorText(MessageBatchErroredResult errored) {
    return errored.error().error()._json().map(Object::toString).orElse("no detail returned");
  }

  private com.anthropic.services.blocking.messages.BatchService batches(String apiKey) {
    AnthropicClient client = clients.forKey(apiKey);
    return client.messages().batches();
  }
}
