package volkovandr.hauptbuch.receipts;

import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Usage;
import java.util.List;

/**
 * The request/response shapes both Anthropic adapters share (9h): the system block (optionally
 * carrying a cache breakpoint), the image + note user turn, and the read-back of a returned
 * message. Single mode and batch mode build different parameter objects but assemble the same
 * blocks, so the prompt they send stays byte-identical — which is what lets a batch share a cached
 * prefix at all.
 *
 * <p><strong>Prompt caching (9h, partially overturning 9e's "no {@code cache_control}"):</strong>
 * the breakpoint goes on the system block — the stable prefix of instructions + AI Vocabulary. The
 * volatile parts (the image and the per-receipt note) follow it in the user turn, so the prefix
 * matches across receipts. Default 5-minute TTL; a cache write costs +25 % and a read 10 %, so it
 * pays from the second request within the window — always worth it for a batch, an explicit choice
 * ("Analyse (cached)") in single mode.
 */
final class AnthropicPrompts {

  /** Ample for a receipt's small TOON body; a low cap risks truncating a long itemised parse. */
  static final long MAX_TOKENS = 4096L;

  /** The image every request carries is the 9c-baked edited JPEG (never re-encoded, §13.1). */
  static final String EDITED_MEDIA_TYPE = "image/jpeg";

  private AnthropicPrompts() {}

  /**
   * The system prompt as a single text block, with a cache breakpoint when {@code cachePrompt} is
   * set. Always a block list (never the plain {@code String} overload) so both shapes render the
   * same way.
   */
  static List<TextBlockParam> systemBlocks(String systemPrompt, boolean cachePrompt) {
    TextBlockParam.Builder block = TextBlockParam.builder().text(systemPrompt);
    if (cachePrompt) {
      block.cacheControl(CacheControlEphemeral.builder().build());
    }
    return List.of(block.build());
  }

  /**
   * The user turn: the receipt image followed by the note. The bytes are sent verbatim (the baked
   * edited JPEG from 9c) — never re-encoded server-side, which would drop the EXIF orientation the
   * model needs.
   */
  static List<ContentBlockParam> userBlocks(String imageBase64, String mediaType, String userText) {
    return List.of(
        ContentBlockParam.ofImage(
            ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .mediaType(mediaTypeOf(mediaType))
                        .data(imageBase64)
                        .build())
                .build()),
        ContentBlockParam.ofText(TextBlockParam.builder().text(userText).build()));
  }

  /** The concatenated text of every text block in the response — the raw TOON body. */
  static String concatText(Message response) {
    StringBuilder out = new StringBuilder();
    response.content().forEach(block -> block.text().ifPresent(t -> out.append(t.text())));
    return out.toString();
  }

  /** The response's raw body plus its four billed token counts (data-model §13.1). */
  static ReceiptParseResult resultOf(Message response) {
    Usage usage = response.usage();
    return new ReceiptParseResult(
        concatText(response),
        (int) usage.inputTokens(),
        (int) usage.outputTokens(),
        usage.cacheCreationInputTokens().orElse(0L).intValue(),
        usage.cacheReadInputTokens().orElse(0L).intValue());
  }

  private static Base64ImageSource.MediaType mediaTypeOf(String mimeType) {
    return "image/png".equalsIgnoreCase(mimeType)
        ? Base64ImageSource.MediaType.IMAGE_PNG
        : Base64ImageSource.MediaType.IMAGE_JPEG;
  }
}
