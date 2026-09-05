package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One currently-resolved cross-currency transfer leg's two real native amounts (import.md §6.3;
 * plan e3) — {@link volkovandr.hauptbuch.importer.repository.ImportMirrorRepository
 * #resolvedCrossCurrencyRateCandidates}'s row shape. The caller offers it to {@code ledger}'s
 * {@code ExchangeRateService#recordObservedRate}, which decides whether the pair states a
 * base-relative rate at all and writes it back — the importer never touches {@code exchange_rate}
 * itself (CLAUDE.md §1: that table belongs to {@code ledger}).
 *
 * @param date the transaction date the rate would be valid for
 * @param currencyA one leg's currency
 * @param amountA that leg's own signed native amount
 * @param currencyB the other leg's currency
 * @param amountB that leg's own signed native amount ({@code counter_amount})
 */
public record ImportCrossCurrencyRateCandidate(
    LocalDate date, String currencyA, BigDecimal amountA, String currencyB, BigDecimal amountB) {}
