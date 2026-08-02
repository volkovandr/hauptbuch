package volkovandr.hauptbuch.receipts;

import dev.toonformat.jtoon.JToon;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decodes the model's raw TOON body into a {@link ParsedReceipt} (stage 9e): jtoon parses the TOON
 * into a plain map/list tree, and this walks that tree into the record shape with lenient coercion
 * — an absent, blank, or empty-object cell (jtoon renders an empty TOON value as {@code {}} and an
 * empty tabular cell as {@code ""}) becomes null, and a value that will not parse into its numeric
 * type is dropped rather than fatal. That leniency is exactly the seeding contract (data-model
 * §13.1). Any body jtoon cannot parse at all yields {@link Optional#empty()} — the worker keeps it
 * in {@code parse_raw} and fails the receipt.
 *
 * <p>Working the tree directly (rather than jtoon's {@code decodeToJson} + typed binding) keeps
 * this off any Jackson version and gives the coercion the leniency typed binding would reject.
 */
@Component
public class ToonReceiptDecoder {

  /** Decode the raw TOON body, or empty when it is blank or jtoon cannot parse it. */
  // AvoidCatchingGenericException: any failure decoding an untrusted model body must yield empty
  // (the worker keeps the raw text and fails the receipt) rather than propagate — that is the
  // point.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public Optional<ParsedReceipt> decode(String rawToon) {
    if (rawToon == null || rawToon.isBlank()) {
      return Optional.empty();
    }
    try {
      Object tree = JToon.decode(unfence(rawToon));
      if (!(tree instanceof Map<?, ?> root)) {
        return Optional.empty();
      }
      return Optional.of(
          new ParsedReceipt(
              merchant(asMap(root.get("merchant"))),
              transaction(asMap(root.get("transaction"))),
              items(root.get("items"))));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static ParsedMerchant merchant(Map<?, ?> m) {
    if (m == null) {
      return null;
    }
    return new ParsedMerchant(str(m.get("name")), str(m.get("city")), str(m.get("country")));
  }

  private static ParsedTransaction transaction(Map<?, ?> t) {
    if (t == null) {
      return null;
    }
    return new ParsedTransaction(
        str(t.get("date")),
        str(t.get("time")),
        str(t.get("account")),
        num(t.get("totalAmount")),
        str(t.get("currency")),
        str(t.get("receiptNumber")));
  }

  private static List<ParsedItem> items(Object raw) {
    List<ParsedItem> parsed = new ArrayList<>();
    if (raw instanceof List<?> rows) {
      for (Object row : rows) {
        Map<?, ?> item = asMap(row);
        if (item != null) {
          parsed.add(
              new ParsedItem(
                  str(item.get("name")),
                  num(item.get("quantity")),
                  num(item.get("unitPrice")),
                  num(item.get("totalPrice")),
                  str(item.get("category")),
                  str(item.get("tags")),
                  str(item.get("beneficiary")),
                  str(item.get("transfer"))));
        }
      }
    }
    return parsed;
  }

  private static Map<?, ?> asMap(Object value) {
    return value instanceof Map<?, ?> map ? map : null;
  }

  /**
   * A scalar as a trimmed string, or null when absent, blank, or an empty object (jtoon's {@code
   * {}}).
   */
  private static String str(Object value) {
    if (value == null || value instanceof Map<?, ?>) {
      return null;
    }
    String text = value.toString().strip();
    return text.isEmpty() ? null : text;
  }

  /** A scalar as a {@link BigDecimal}, or null when absent, blank, or not a number. */
  private static BigDecimal num(Object value) {
    String text = str(value);
    if (text == null) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Strip a leading/trailing Markdown code fence the model may have wrapped the TOON in. */
  private static String unfence(String body) {
    String trimmed = body.strip();
    if (!trimmed.startsWith("```")) {
      return trimmed;
    }
    int firstNewline = trimmed.indexOf('\n');
    if (firstNewline < 0) {
      return trimmed;
    }
    String withoutOpen = trimmed.substring(firstNewline + 1);
    int lastFence = withoutOpen.lastIndexOf("```");
    return (lastFence < 0 ? withoutOpen : withoutOpen.substring(0, lastFence)).strip();
  }
}
