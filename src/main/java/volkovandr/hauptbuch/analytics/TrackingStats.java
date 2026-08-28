package volkovandr.hauptbuch.analytics;

/**
 * The rendered pieces of the landing-page tracking-stats line (CONTEXT.md "Tracking stats"):
 *
 * <pre>
 *   Keeping track of your finances for {durationPhrase} — {transactionsPhrase}, {receiptsPhrase}.
 * </pre>
 *
 * <p>{@code receiptsPhrase} is {@code null} when no receipt has been analyzed yet — the template
 * then drops the clause and the sentence ends after the transaction count.
 *
 * @param durationPhrase the span since the earliest transaction, e.g. {@code "2 years and 8
 *     months"} or {@code "less than a month"}
 * @param transactionsPhrase the live transaction count with its noun, e.g. {@code "1.234
 *     transactions"}
 * @param receiptsPhrase the analyzed-receipt count with storage size, e.g. {@code "800 receipts
 *     analyzed (2,5 GB)"}, or {@code null}
 */
public record TrackingStats(
    String durationPhrase, String transactionsPhrase, String receiptsPhrase) {}
