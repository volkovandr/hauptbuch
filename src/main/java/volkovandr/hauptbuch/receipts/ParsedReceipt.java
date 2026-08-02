package volkovandr.hauptbuch.receipts;

import java.util.List;

/**
 * The decoded shape of a receipt parse (stage 9e, data-model §13.1/§13.2): {@code
 * ToonReceiptDecoder} walks jtoon's parsed tree into this. Every field is best-effort — the parser
 * is instructed to leave anything it cannot read blank, and the decoder coerces leniently — so an
 * absent value is simply null.
 *
 * @param merchant the parsed merchant block, or null
 * @param transaction the parsed transaction block, or null
 * @param items the parsed line items (possibly empty)
 */
public record ParsedReceipt(
    ParsedMerchant merchant, ParsedTransaction transaction, List<ParsedItem> items) {

  /** Defensive copy + null-safety for the item list (the house pattern for record lists). */
  public ParsedReceipt {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
