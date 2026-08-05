package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): the request blocks both Anthropic adapters assemble (stage 9h). Pure
 * shape-building, so it tests without the network — and it is where the cache breakpoint either
 * lands or does not, which is the whole of the "Analyse (cached)" choice.
 */
class AnthropicPromptsTest {

  @Test
  void plainSystemBlockCarriesNoCacheBreakpoint() {
    List<TextBlockParam> blocks = AnthropicPrompts.systemBlocks("instructions", false);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).text()).isEqualTo("instructions");
    assertThat(blocks.get(0).cacheControl()).isEmpty();
  }

  /** The breakpoint goes on the system block — the stable prefix — and nowhere else. */
  @Test
  void cachedSystemBlockCarriesTheBreakpoint() {
    List<TextBlockParam> blocks = AnthropicPrompts.systemBlocks("instructions", true);

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).text()).isEqualTo("instructions");
    assertThat(blocks.get(0).cacheControl()).isPresent();
  }

  /**
   * The user turn is image first, note second — the volatile part, after the cacheable prefix, so a
   * batch's members share everything up to the breakpoint.
   */
  @Test
  void userTurnIsImageThenNote() {
    List<ContentBlockParam> blocks =
        AnthropicPrompts.userBlocks("YmFzZTY0", "image/jpeg", "Parse this receipt. it is fuel");

    assertThat(blocks).hasSize(2);
    assertThat(blocks.get(0).isImage()).isTrue();
    assertThat(blocks.get(1).text().orElseThrow().text())
        .isEqualTo("Parse this receipt. it is fuel");
  }
}
