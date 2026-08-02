package volkovandr.hauptbuch.receipts;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * The production {@link ReceiptParser} (stage 9e): one blocking Messages-API call via the official
 * Anthropic Java SDK (ARCH-03). Thin by design — assemble the request (system prompt + image +
 * note), read back the raw text and usage — so all judgement (prompt building, TOON decoding,
 * lenient seeding) lives in unit-tested collaborators and this adapter carries no branching logic
 * to test without the network.
 *
 * <p>The image bytes are sent verbatim (the baked edited JPEG from 9c) — never re-encoded
 * server-side, which would drop the EXIF orientation the model needs. No {@code cache_control} in
 * 9e (a batch's shared prefix earns the breakpoint in 9h); {@code thinking} is left at the model
 * default so the request stays valid across whatever model the operator configures.
 */
@Component
class AnthropicReceiptParser implements ReceiptParser {

  /** Ample for a receipt's small TOON body; a low cap risks truncating a long itemised parse. */
  private static final long MAX_TOKENS = 4096L;

  private final ReentrantLock clientLock = new ReentrantLock();
  private String cachedKey;
  private AnthropicClient cachedClient;

  @Override
  public ReceiptParseResult parse(ReceiptParseRequest request, byte[] imageBytes) {
    if (request.apiKey() == null || request.apiKey().isBlank()) {
      throw new ReceiptParseException(
          "No Anthropic API key configured — set one on the Settings screen"
              + " or in ANTHROPIC_API_KEY");
    }
    MessageCreateParams params =
        MessageCreateParams.builder()
            .model(request.model())
            .maxTokens(MAX_TOKENS)
            .system(request.systemPrompt())
            .addUserMessageOfBlockParams(
                List.of(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(mediaTypeOf(request.mediaType()))
                                    .data(Base64.getEncoder().encodeToString(imageBytes))
                                    .build())
                            .build()),
                    ContentBlockParam.ofText(
                        TextBlockParam.builder().text(request.userText()).build())))
            .build();

    try {
      Message response = clientFor(request.apiKey()).messages().create(params);
      return new ReceiptParseResult(
          concatText(response),
          (int) response.usage().inputTokens(),
          (int) response.usage().outputTokens(),
          cacheWrite(response.usage()),
          cacheRead(response.usage()));
    } catch (AnthropicException e) {
      // Any SDK/transport failure (network, auth, overloaded) becomes our parse failure; a
      // non-SDK RuntimeException bubbles to the worker's own guard, which also fails the receipt.
      throw new ReceiptParseException("Receipt parse call failed: " + e.getMessage(), e);
    }
  }

  /** The concatenated text of every text block in the response — the raw TOON body. */
  private static String concatText(Message response) {
    StringBuilder out = new StringBuilder();
    response.content().forEach(block -> block.text().ifPresent(t -> out.append(t.text())));
    return out.toString();
  }

  private static int cacheWrite(Usage usage) {
    return usage.cacheCreationInputTokens().orElse(0L).intValue();
  }

  private static int cacheRead(Usage usage) {
    return usage.cacheReadInputTokens().orElse(0L).intValue();
  }

  private static Base64ImageSource.MediaType mediaTypeOf(String mimeType) {
    return "image/png".equalsIgnoreCase(mimeType)
        ? Base64ImageSource.MediaType.IMAGE_PNG
        : Base64ImageSource.MediaType.IMAGE_JPEG;
  }

  /**
   * A client cached by key — rebuilt only when the operator rotates it (rare on a single-user Pi).
   */
  private AnthropicClient clientFor(String apiKey) {
    clientLock.lock();
    try {
      if (cachedClient == null || !apiKey.equals(cachedKey)) {
        cachedClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        cachedKey = apiKey;
      }
      return cachedClient;
    } finally {
      clientLock.unlock();
    }
  }
}
