package volkovandr.hauptbuch.importer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.importer.repository.ImportMirrorRepository;
import volkovandr.hauptbuch.ledger.ExchangeRateService;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportCrossCurrencyRateWriteBackService} — the plan-e3 seam
 * {@link ImportMirrorMatchingService} and {@link ImportCrossCurrencyParkService} both trigger.
 * Every {@link ImportCrossCurrencyRateCandidate} the repository reports is offered to {@code
 * ledger}'s {@link ExchangeRateService#recordObservedRate}; {@code ledger}, not this class, decides
 * whether it states a rate.
 */
@ExtendWith(MockitoExtension.class)
class ImportCrossCurrencyRateWriteBackServiceTest {

  private static final long SESSION_ID = 7L;

  @Mock ImportMirrorRepository importMirrorRepository;
  @Mock ExchangeRateService exchangeRateService;

  private ImportCrossCurrencyRateWriteBackService service() {
    return new ImportCrossCurrencyRateWriteBackService(importMirrorRepository, exchangeRateService);
  }

  @Test
  void offersEveryResolvedCandidateToExchangeRateService() {
    ImportCrossCurrencyRateCandidate candidate =
        new ImportCrossCurrencyRateCandidate(
            LocalDate.of(2026, 5, 5),
            "EUR",
            new BigDecimal("100.00"),
            "CHF",
            new BigDecimal("150.00"));
    when(importMirrorRepository.resolvedCrossCurrencyRateCandidates(SESSION_ID))
        .thenReturn(List.of(candidate));

    service().writeBackObservedRates(SESSION_ID);

    verify(exchangeRateService)
        .recordObservedRate(
            LocalDate.of(2026, 5, 5),
            "EUR",
            new BigDecimal("100.00"),
            "CHF",
            new BigDecimal("150.00"));
  }

  @Test
  void offersNothingWhenNoCandidateIsResolved() {
    // resolvedCrossCurrencyRateCandidates is unstubbed — Mockito's default answer for a
    // List-returning method is an empty list, matching "nothing currently resolved".
    service().writeBackObservedRates(SESSION_ID);

    verify(exchangeRateService, never()).recordObservedRate(any(), any(), any(), any(), any());
  }
}
