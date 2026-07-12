package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.ledger.repository.ExchangeRateRepository;

/**
 * The book's currency-conversion rate lookup (data-model §3.7) — {@code ledger} owns the {@code
 * exchange_rate} carry-forward cache, so this is its public read API for the rest of the app: the
 * cross-currency entry fields' rate proposal (plan stage 7d.1), and later held-balance revaluation.
 *
 * <p>{@link #rateAsOf} is the carry-forward lookup itself (most recent stored rate on or before a
 * date); it never writes a rate. A base-currency leg needs no lookup at all — the caller already
 * knows it and never calls through here for it (data-model §6.1).
 */
@Service
public class ExchangeRateService {

  private final ExchangeRateRepository exchangeRateRepository;

  ExchangeRateService(ExchangeRateRepository exchangeRateRepository) {
    this.exchangeRateRepository = exchangeRateRepository;
  }

  /**
   * The rate (units of base per 1 unit of {@code currencyCode}) in effect on {@code date}: the most
   * recent stored rate on or before it. Empty when no rate has been recorded on or before the date.
   */
  public Optional<BigDecimal> rateAsOf(String currencyCode, LocalDate date) {
    return exchangeRateRepository.rateAsOf(currencyCode, date);
  }
}
