package volkovandr.hauptbuch.receipts;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * The one place an {@link AnthropicClient} is built, shared by the single-parse adapter ({@link
 * AnthropicReceiptParser}) and the batch adapter ({@code AnthropicReceiptBatchClient}, 9h). The
 * client is cached by key and rebuilt only when the operator rotates it — rare on a single-user Pi.
 */
@Component
class AnthropicClients {

  private final ReentrantLock lock = new ReentrantLock();
  private String cachedKey;
  private AnthropicClient cachedClient;

  /**
   * The client for {@code apiKey}, built on first use and reused thereafter.
   *
   * @throws ReceiptParseException if no key is configured (neither the settings row nor the env)
   */
  AnthropicClient forKey(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new ReceiptParseException(
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
