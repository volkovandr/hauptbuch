package volkovandr.hauptbuch.receipts;

/**
 * The merchant block of a receipt parse (stage 9e) — seeds the denormalised header fields {@code
 * merchant_text} / {@code merchant_city} / {@code merchant_country} (data-model §13.1). Any field
 * may be null when the model could not read it.
 *
 * @param name the merchant name
 * @param city the merchant city
 * @param country the merchant country
 */
public record ParsedMerchant(String name, String city, String country) {}
