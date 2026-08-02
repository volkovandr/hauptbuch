package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;

/**
 * The transaction block of a receipt parse (stage 9e) — seeds the denormalised header {@code
 * receipt_date} / {@code receipt_time} / {@code total_amount} / {@code currency_code} and, via
 * {@code account}, the detected paying account (data-model §13.1/§13.4). Every field is
 * best-effort; dates/times/amounts that fail to parse into their target type stay null (lenient
 * seeding).
 *
 * @param date the receipt date, ISO {@code yyyy-MM-dd}
 * @param time the printed time, {@code HH:mm}
 * @param account the payment-line signal ({@code card XXXX1234} / {@code Bar}) — resolved to the
 *     paying account through §13.4 detection, never a real account name
 * @param totalAmount the printed total
 * @param currency the ISO currency code
 * @param receiptNumber the printed receipt identifier (Beleg-Nr.)
 */
public record ParsedTransaction(
    String date,
    String time,
    String account,
    BigDecimal totalAmount,
    String currency,
    String receiptNumber) {}
