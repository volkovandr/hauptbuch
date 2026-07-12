package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Resolves the entry dock's {@link CrossCurrencyFields} for a category-currency selection (register
 * §3.5/§3.8a): whether the transaction is cross-currency and, when neither leg is the book's base
 * currency, a base-amount pre-filled from {@link ExchangeRateService#rateAsOf} so the user only has
 * to confirm it. Shared by the currency-select htmx endpoint and error redisplay in {@code
 * operations} — both must compute the same layout the same way.
 */
@Service
public class CrossCurrencyFieldsService {

  /** Dock amounts are entered German-formatted to the minor unit; two places covers EUR/CHF. */
  private static final int AMOUNT_FRACTION_DIGITS = 2;

  /** The Unicode minus sign, accepted alongside the ASCII hyphen-minus (register §3.8). */
  private static final char UNICODE_MINUS = '−';

  private final SettingsService settingsService;
  private final ExchangeRateService exchangeRateService;

  CrossCurrencyFieldsService(
      SettingsService settingsService, ExchangeRateService exchangeRateService) {
    this.settingsService = settingsService;
    this.exchangeRateService = exchangeRateService;
  }

  /**
   * Resolve the field layout for a funding/category currency pair, redisplaying any already-typed
   * category/base amount text (an error redisplay or a currency-select change carries these along).
   */
  public CrossCurrencyFields resolve(CrossCurrencyFieldsQuery query) {
    String fundingCurrencyCode = query.fundingCurrencyCode();
    String targetCurrency =
        isBlank(query.categoryCurrencyCode()) ? fundingCurrencyCode : query.categoryCurrencyCode();
    if (fundingCurrencyCode.equals(targetCurrency)) {
      return CrossCurrencyFields.singleCurrency(fundingCurrencyCode);
    }

    String baseCurrency = settingsService.baseCurrency().orElse(null);
    boolean neitherIsBase =
        baseCurrency != null
            && !fundingCurrencyCode.equals(baseCurrency)
            && !targetCurrency.equals(baseCurrency);

    String resolvedBaseText = query.baseAmountText();
    if (neitherIsBase && isBlank(resolvedBaseText)) {
      resolvedBaseText = prefillBase(fundingCurrencyCode, query.date(), query.fundingAmountText());
    }
    return new CrossCurrencyFields(
        fundingCurrencyCode,
        targetCurrency,
        true,
        neitherIsBase,
        query.categoryAmountText(),
        resolvedBaseText);
  }

  /**
   * Propose the base amount from the carry-forward rate feed; blank when nothing can be derived.
   */
  private String prefillBase(String fundingCurrencyCode, LocalDate date, String fundingAmountText) {
    if (date == null) {
      return null;
    }
    Optional<BigDecimal> magnitude = tryParseMagnitude(fundingAmountText);
    if (magnitude.isEmpty()) {
      return null;
    }
    return exchangeRateService
        .rateAsOf(fundingCurrencyCode, date)
        .map(rate -> MoneyFormat.number(magnitude.get().multiply(rate), AMOUNT_FRACTION_DIGITS))
        .orElse(null);
  }

  /** A best-effort magnitude parse for the prefill — never throws on blank or malformed text. */
  private static Optional<BigDecimal> tryParseMagnitude(String text) {
    if (isBlank(text)) {
      return Optional.empty();
    }
    String trimmed = text.strip();
    char first = trimmed.charAt(0);
    boolean signed = first == '+' || first == '-' || first == UNICODE_MINUS;
    String magnitudeText = signed ? trimmed.substring(1).strip() : trimmed;
    try {
      return Optional.of(MoneyFormat.parse(magnitudeText).abs());
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
