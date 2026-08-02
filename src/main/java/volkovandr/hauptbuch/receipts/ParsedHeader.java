package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The denormalised header the seeder produced from a parse (data-model §13.1) — every field
 * best-effort, staying null when the model left it blank or it failed to parse into its type
 * (lenient seeding). {@code accountId} is the paying account detected from the payment-line signal
 * (§13.4), or null when nothing matched (the operator then picks in post-process).
 *
 * @param merchantText parsed merchant name
 * @param merchantCity parsed merchant city
 * @param merchantCountry parsed merchant country
 * @param receiptDate parsed receipt date
 * @param receiptTime parsed printed time
 * @param receiptNumber parsed printed receipt number
 * @param totalAmount parsed total
 * @param currencyCode parsed ISO currency code
 * @param accountId detected paying account, or null
 */
public record ParsedHeader(
    String merchantText,
    String merchantCity,
    String merchantCountry,
    LocalDate receiptDate,
    LocalTime receiptTime,
    String receiptNumber,
    BigDecimal totalAmount,
    String currencyCode,
    Long accountId) {}
