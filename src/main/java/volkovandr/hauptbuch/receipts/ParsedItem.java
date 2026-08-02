package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;

/**
 * One parsed line item (stage 9e, data-model §13.2). Seeds a {@code receipt_line}: {@code name}
 * (folded with {@code quantity} into the description), {@code totalPrice} (the amount), {@code
 * category} (resolved leaves-only via the AI Vocabulary), note-instructed {@code tags} and {@code
 * beneficiary} echoes, and a {@code transfer} signal (§13.4). {@code quantity}/{@code unitPrice}
 * otherwise live only in {@code parse_raw}.
 *
 * <p>{@code tags} is a raw string rather than a list: TOON's tabular arrays carry scalar cells, so
 * the model emits echoed tag paths as one delimited cell — seeding splits it on {@code ,}/{@code ;}
 * and resolves each {@code Parent:Child} path non-creatingly. Beneficiaries and categories are
 * single-valued.
 *
 * @param name the item name
 * @param quantity the quantity, or null (folded into the description as {@code N× …} when {@code >
 *     1})
 * @param unitPrice the unit price, kept only in {@code parse_raw}
 * @param totalPrice the line total — the seeded {@code amount}
 * @param category the echoed effective category path, or blank (uncategorised)
 * @param tags a delimited list of echoed {@code Parent:Child} tag paths, or blank
 * @param beneficiary the echoed person name (a beneficiary leg), or blank
 * @param transfer the echoed transfer signal ({@code cash} / a card last-4), or blank
 */
public record ParsedItem(
    String name,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal totalPrice,
    String category,
    String tags,
    String beneficiary,
    String transfer) {}
