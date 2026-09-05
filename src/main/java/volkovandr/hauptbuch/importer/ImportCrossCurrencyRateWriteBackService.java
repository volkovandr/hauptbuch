package volkovandr.hauptbuch.importer;

import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.importer.repository.ImportMirrorRepository;
import volkovandr.hauptbuch.ledger.ExchangeRateService;

/**
 * Offers every currently-resolved cross-currency leg of a session to {@code ledger}'s {@link
 * ExchangeRateService#recordObservedRate} (import.md §6.3; plan e3) — the one seam {@link
 * ImportMirrorMatchingService} (after a rematch) and {@link ImportCrossCurrencyParkService} (after
 * a successful manual match or hand-entered close) share, so the write-back rule lives in exactly
 * one place rather than two copies drifting apart. Safe and cheap to re-run after any resolution:
 * {@code recordObservedRate} never overwrites an existing {@code exchange_rate} row.
 */
@Service
class ImportCrossCurrencyRateWriteBackService {

  private final ImportMirrorRepository importMirrorRepository;
  private final ExchangeRateService exchangeRateService;

  ImportCrossCurrencyRateWriteBackService(
      ImportMirrorRepository importMirrorRepository, ExchangeRateService exchangeRateService) {
    this.importMirrorRepository = importMirrorRepository;
    this.exchangeRateService = exchangeRateService;
  }

  void writeBackObservedRates(long importSessionId) {
    importMirrorRepository
        .resolvedCrossCurrencyRateCandidates(importSessionId)
        .forEach(
            c ->
                exchangeRateService.recordObservedRate(
                    c.date(), c.currencyA(), c.amountA(), c.currencyB(), c.amountB()));
  }
}
