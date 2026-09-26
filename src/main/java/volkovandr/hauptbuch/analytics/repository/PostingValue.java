package volkovandr.hauptbuch.analytics.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One posting of a drill-down list (reporting.md §12), valued by the turnover rule (data-model
 * §6.1): its own frozen {@code base_amount} when it has one, else its amount at its own date's
 * rate.
 *
 * @param postingId the posting
 * @param transactionId its transaction
 * @param date its transaction's date
 * @param currencyCode its account's currency
 * @param amount its signed native amount
 * @param baseAmount its value in the base currency; {@code null} when no rate covers its date
 */
public record PostingValue(
    long postingId,
    long transactionId,
    LocalDate date,
    String currencyCode,
    BigDecimal amount,
    BigDecimal baseAmount) {}
