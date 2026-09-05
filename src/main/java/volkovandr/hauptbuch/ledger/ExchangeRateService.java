package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.ledger.repository.ExchangeRateRepository;

/**
 * The book's currency-conversion rate lookup and write-back (data-model §3.7) — {@code ledger} owns
 * the {@code exchange_rate} carry-forward cache, so this is its public API for the rest of the app:
 * the cross-currency entry fields' rate proposal (plan stage 7d.1), later held-balance revaluation,
 * and the importer's observed-rate write-back (plan e3).
 *
 * <p>{@link #rateAsOf} is the carry-forward lookup itself (most recent stored rate on or before a
 * date); it never writes a rate. A base-currency leg needs no lookup at all — the caller already
 * knows it and never calls through here for it (data-model §6.1).
 *
 * <p>{@link #recordObservedRate} is the one sanctioned write path from outside {@code ledger}:
 * every other module hands over the two real native amounts of an event it witnessed (currently
 * only the importer, via a resolved cross-currency transfer) and {@code ledger} alone decides
 * whether that pair states a rate and persists it — no other module writes to {@code exchange_rate}
 * directly (CLAUDE.md §1).
 */
@Service
public class ExchangeRateService {

  private static final Logger LOG = LoggerFactory.getLogger(ExchangeRateService.class);

  /** The scale of the persisted {@code exchange_rate.rate} column ({@code numeric(19,8)}). */
  private static final int RATE_SCALE = 8;

  /** {@code exchange_rate.source} for a rate implied by a real observed event, not the ECB feed. */
  private static final String OBSERVED_SOURCE = "import";

  private final ExchangeRateRepository exchangeRateRepository;
  private final SettingsService settingsService;

  ExchangeRateService(
      ExchangeRateRepository exchangeRateRepository, SettingsService settingsService) {
    this.exchangeRateRepository = exchangeRateRepository;
    this.settingsService = settingsService;
  }

  /**
   * The rate (units of base per 1 unit of {@code currencyCode}) in effect on {@code date}: the most
   * recent stored rate on or before it. Empty when no rate has been recorded on or before the date.
   */
  public Optional<BigDecimal> rateAsOf(String currencyCode, LocalDate date) {
    return exchangeRateRepository.rateAsOf(currencyCode, date);
  }

  /**
   * Record a rate implied by two real native amounts of the same event on {@code date} — e.g. the
   * importer's resolved cross-currency transfer pair (import.md §6.3; plan e3) — whenever one of
   * the two currencies is the book's base currency (data-model §3.8): the pair then directly states
   * "this many base units for that many foreign units," the actual rate of a real event, not a
   * guess. A no-op when the base currency is not yet set, when <strong>neither</strong> currency is
   * base (import.md §6.5, Q-IMP-4 — the pair alone cannot state a base-relative rate), when the two
   * currencies are the same (nothing to convert), or when either amount is zero. Never overwrites
   * an existing row for that {@code (currency_code, date)} (§3.7): an ECB or manual rate already on
   * file for the day is left alone — including one from an earlier, different observed pair for the
   * same day, since the cache holds only one rate per {@code (currency, date)}.
   */
  public void recordObservedRate(
      LocalDate date, String currencyA, BigDecimal amountA, String currencyB, BigDecimal amountB) {
    if (currencyA.equals(currencyB)) {
      return;
    }
    Optional<String> base = settingsService.baseCurrency();
    if (base.isEmpty()) {
      return;
    }
    Optional<ForeignLeg> leg = foreignLeg(base.get(), currencyA, amountA, currencyB, amountB);
    if (leg.isEmpty() || !leg.get().isUsable()) {
      return;
    }
    ForeignLeg foreignLeg = leg.get();
    BigDecimal rate =
        foreignLeg
            .baseValueAmount()
            .abs()
            .divide(foreignLeg.foreignAmount().abs(), RATE_SCALE, RoundingMode.HALF_UP);
    boolean inserted =
        exchangeRateRepository.insertIfAbsent(
            new ExchangeRate(null, foreignLeg.currencyCode(), date, rate, OBSERVED_SOURCE));
    LOG.debug(
        "Observed rate for {} on {}: {} ({})",
        foreignLeg.currencyCode(),
        date,
        rate,
        inserted ? "recorded" : "already on file, kept");
  }

  /**
   * Which of the pair's two legs is the foreign one, given the base currency — empty when neither
   * {@code currencyA} nor {@code currencyB} is base.
   *
   * @param currencyCode the non-base currency
   * @param foreignAmount that leg's own native amount
   * @param baseValueAmount the other leg's amount, denominated in base
   */
  private record ForeignLeg(
      String currencyCode, BigDecimal foreignAmount, BigDecimal baseValueAmount) {

    /** Both amounts are present and nonzero — a zero or missing leg states no usable rate. */
    boolean isUsable() {
      return foreignAmount != null
          && foreignAmount.signum() != 0
          && baseValueAmount != null
          && baseValueAmount.signum() != 0;
    }
  }

  private static Optional<ForeignLeg> foreignLeg(
      String baseCurrency,
      String currencyA,
      BigDecimal amountA,
      String currencyB,
      BigDecimal amountB) {
    if (baseCurrency.equals(currencyA)) {
      return Optional.of(new ForeignLeg(currencyB, amountB, amountA));
    }
    if (baseCurrency.equals(currencyB)) {
      return Optional.of(new ForeignLeg(currencyA, amountA, amountB));
    }
    return Optional.empty();
  }
}
