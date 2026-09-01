package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The per-account verification device (import.md §9.4; plan e′): for one Money account, across
 * every file staged for it in the campaign, the transaction count, the net sum of the funding legs
 * and the date range. The owner ticks {@code netSum} against Money's own balance for that account —
 * the cheapest check that the parser read a 20-year-old export correctly, run with zero ledger risk
 * before any mapping is built.
 *
 * <p>{@code netSum} sums only the synthesised funding legs ({@link ImportPosting#funding()}) — the
 * per-transaction totals in Money's own {@code T} — so a transfer's mirror leg staged from the
 * <em>other</em> account's file is not double-counted here.
 *
 * @param moneyAccountName the Money account name — the map key (import.md §5.1)
 * @param transactionCount the number of staged transactions for this account
 * @param netSum the sum of the funding-leg amounts, in Hauptbuch's sign convention (§7)
 * @param firstDate the earliest staged transaction date
 * @param lastDate the latest staged transaction date
 */
public record ImportAccountStatistics(
    String moneyAccountName,
    long transactionCount,
    BigDecimal netSum,
    LocalDate firstDate,
    LocalDate lastDate) {}
