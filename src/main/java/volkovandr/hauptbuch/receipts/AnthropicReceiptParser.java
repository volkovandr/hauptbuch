package volkovandr.hauptbuch.receipts;

import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * The production {@link ReceiptParser} (stage 9e): one blocking Messages-API call via the official
 * Anthropic Java SDK (ARCH-03). Thin by design — assemble the request (system prompt + image +
 * note), read back the raw text and usage — so all judgement (prompt building, TOON decoding,
 * lenient seeding) lives in unit-tested collaborators and this adapter carries no branching logic
 * to test without the network.
 *
 * <p>The blocks themselves come from {@link AnthropicPrompts}, shared with the batch adapter, so
 * single mode and batch mode send a byte-identical prefix. Whether that prefix carries a cache
 * breakpoint is the request's {@code cachePrompt} flag — the "Analyse (cached)" button (9h). {@code
 * thinking} is left at the model default so the request stays valid across whatever model the
 * operator configures.
 */
@Component
class AnthropicReceiptParser implements ReceiptParser {

  private final AnthropicClients clients;
  private final AnthropicProperties properties;

  AnthropicReceiptParser(AnthropicClients clients, AnthropicProperties properties) {
    this.clients = clients;
    this.properties = properties;
  }

  @Override
  public ReceiptParseResult parse(ReceiptParseRequest request, byte[] imageBytes) {
    MessageCreateParams params =
        MessageCreateParams.builder()
            .model(request.model())
            .maxTokens(properties.maxTokens())
            .systemOfTextBlockParams(
                AnthropicPrompts.systemBlocks(request.systemPrompt(), request.cachePrompt()))
            .addUserMessageOfBlockParams(
                AnthropicPrompts.userBlocks(
                    Base64.getEncoder().encodeToString(imageBytes),
                    request.mediaType(),
                    request.userText()))
            .build();

    try {
      Message response = clients.forKey(request.apiKey()).messages().create(params);
      return AnthropicPrompts.resultOf(response);
    } catch (AnthropicException e) {
      // Any SDK/transport failure (network, auth, overloaded) becomes our parse failure; a
      // non-SDK RuntimeException bubbles to the worker's own guard, which also fails the receipt.
      throw new ReceiptParseException("Receipt parse call failed: " + e.getMessage(), e);
    }
  }
}
