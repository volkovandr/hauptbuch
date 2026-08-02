package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): TOON decoding + record mapping (stage 9e). Confirms a well-formed body
 * maps to the {@link ParsedReceipt} tree, a fenced body is unwrapped, and anything undecodable
 * yields empty (the worker then keeps {@code parse_raw} and fails the receipt, data-model §13.1)
 * rather than throwing.
 */
class ToonReceiptDecoderTest {

  private final ToonReceiptDecoder decoder = new ToonReceiptDecoder();

  private static final String SAMPLE =
      """
      merchant:
        name: Total Tankstelle
        city:
        country: Germany
      transaction:
        date: 2026-07-21
        time: 12:13
        account: card XXXX1234
        totalAmount: 45.67
        currency: EUR
        receiptNumber: 4711
      items[3]{name,quantity,unitPrice,totalPrice,category}:
        Big Bear's Tasty Bread,2,0.7,1.4,Food - Non Sweets
        Coca Cola Zero,,,2.13,Food - Drinks
        Diesel Fuel,21.948,1.92,42.14,Car - Fuel
      """;

  @Test
  void decodesTheSampleIntoTheRecordTree() {
    Optional<ParsedReceipt> decoded = decoder.decode(SAMPLE);

    assertThat(decoded).isPresent();
    ParsedReceipt receipt = decoded.orElseThrow();
    assertThat(receipt.merchant().name()).isEqualTo("Total Tankstelle");
    assertThat(receipt.merchant().country()).isEqualTo("Germany");
    assertThat(receipt.transaction().totalAmount()).isEqualByComparingTo("45.67");
    assertThat(receipt.transaction().account()).isEqualTo("card XXXX1234");
    assertThat(receipt.items()).hasSize(3);
    assertThat(receipt.items().get(0).name()).isEqualTo("Big Bear's Tasty Bread");
    assertThat(receipt.items().get(0).totalPrice()).isEqualByComparingTo("1.4");
    assertThat(receipt.items().get(0).category()).isEqualTo("Food - Non Sweets");
    assertThat(receipt.items().get(2).quantity()).isEqualByComparingTo("21.948");
  }

  @Test
  void unwrapsMarkdownFence() {
    String fenced = "```toon\n" + SAMPLE + "```";

    assertThat(decoder.decode(fenced)).isPresent();
  }

  @Test
  void returnsEmptyForBlankBody() {
    assertThat(decoder.decode("  ")).isEmpty();
    assertThat(decoder.decode(null)).isEmpty();
  }

  @Test
  void returnsEmptyForNonObjectBody() {
    // A bare scalar (the model returned prose or a number, not the receipt object shape).
    assertThat(decoder.decode("42")).isEmpty();
    assertThat(decoder.decode("just some prose, no structure")).isEmpty();
  }
}
