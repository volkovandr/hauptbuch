package volkovandr.hauptbuch.statements;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Usage;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The production {@link StatementParser}: one blocking Messages-API call via the official Anthropic
 * Java SDK (ARCH-03). Thin by design — assemble the request, read back the raw text and usage — so
 * every judgement (prompt, TOON decoding, seeding) lives in unit-tested collaborators. The client
 * is cached by key and rebuilt only when the operator rotates it.
 */
@Component
class AnthropicStatementParser implements StatementParser {

  private static final Logger LOG = LoggerFactory.getLogger(AnthropicStatementParser.class);

  /** Covers adaptive thinking as well as the visible TOON of a month of lines. */
  private static final long MAX_TOKENS = 16_384;

  private final ReentrantLock lock = new ReentrantLock();
  private String cachedKey;
  private AnthropicClient cachedClient;

  @Override
  public StatementParseResult parse(StatementParseRequest request) {
    MessageCreateParams params =
        MessageCreateParams.builder()
            .model(request.model())
            .maxTokens(MAX_TOKENS)
            .system(request.systemPrompt())
            .addUserMessage(request.userText())
            .build();
    LOG.debug("Statement parse request: model={}", request.model());
    try {
      Message response = clientFor(request.apiKey()).messages().create(params);
      StatementParseResult result = resultOf(response);
      LOG.debug("Statement parse response: {}", result.rawToon());
      return result;
    } catch (AnthropicException e) {
      throw new StatementParseException("Statement parse call failed: " + e.getMessage(), e);
    }
  }

  private static StatementParseResult resultOf(Message response) {
    StringBuilder text = new StringBuilder();
    response.content().forEach(block -> block.text().ifPresent(t -> text.append(t.text())));
    Usage usage = response.usage();
    return new StatementParseResult(
        text.toString(),
        (int) usage.inputTokens(),
        (int) usage.outputTokens(),
        usage.cacheCreationInputTokens().orElse(0L).intValue(),
        usage.cacheReadInputTokens().orElse(0L).intValue());
  }

  private AnthropicClient clientFor(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new StatementParseException(
          "No Anthropic API key configured — set one on the Settings screen"
              + " or in ANTHROPIC_API_KEY");
    }
    lock.lock();
    try {
      if (cachedClient == null || !apiKey.equals(cachedKey)) {
        cachedClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        cachedKey = apiKey;
      }
      return cachedClient;
    } finally {
      lock.unlock();
    }
  }
}
