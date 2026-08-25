package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *
 * <p>The rate proposals live here rather than in either entry surface, so the register's split
 * panel and the receipt editor propose the same number from the same feed (issue receipts/23,
 * decision 6): {@link #prefillFundingTotal} proposes what comes off the account from what the
 * receipt says, and {@code prefillBase} (through {@link #resolve}) proposes the base figure from
 * the funding one. Both are lenient — a blank or malformed input, or a leg with no stored rate on
 * or before the date, yields no proposal rather than a guess.
 */
@Service
public class CrossCurrencyFieldsService {

  /** Dock amounts are entered German-formatted to the minor unit; two places covers EUR/CHF. */
  private static final int AMOUNT_FRACTION_DIGITS = 2;

  /** Intermediate scale for the base → funding division, before rounding to the minor unit. */
  private static final int RATE_SCALE = 10;

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
   * Propose the funding-currency total from a spending-currency total (issue receipts/23) — the
   * sibling of {@link #prefillBase}, in the direction the split panel and the receipt editor need:
   * the operator knows what the receipt says, not yet what came off the card.
   *
   * <p>Rates are stored only against base ({@code units of BASE per 1 unit of currency_code}), so
   * spending → funding triangulates through base <em>here</em> rather than in every caller. When
   * one leg already <em>is</em> the base currency that collapses to a single lookup. Returns null —
   * never a guess — when either leg has no stored rate on or before the date, when no base currency
   * is set, or when the text does not parse; a blank field is the honest answer, and the Confirm
   * gate blocks until the operator fills it.
   *
   * @param fundingCurrencyCode the paying account's currency (what the proposal is denominated in)
   * @param spendingCurrencyCode the currency the receipt is billed in
   * @param date the transaction date, to look the rates up as of it; may be null
   * @param spendingTotalText the spending-currency total as typed; may be null or malformed
   */
  public String prefillFundingTotal(
      String fundingCurrencyCode,
      String spendingCurrencyCode,
      LocalDate date,
      String spendingTotalText) {
    if (date == null || isBlank(fundingCurrencyCode) || isBlank(spendingCurrencyCode)) {
      return null;
    }
    if (fundingCurrencyCode.equals(spendingCurrencyCode)) {
      return null; // single-currency: there is no second total to propose
    }
    Optional<BigDecimal> spendingTotal = tryParseMagnitude(spendingTotalText);
    String baseCurrency = settingsService.baseCurrency().orElse(null);
    if (spendingTotal.isEmpty() || baseCurrency == null) {
      return null;
    }
    BigDecimal inBase = toBase(spendingTotal.get(), spendingCurrencyCode, baseCurrency, date);
    if (inBase == null) {
      return null;
    }
    BigDecimal funding = fromBase(inBase, fundingCurrencyCode, baseCurrency, date);
    return funding == null ? null : MoneyFormat.number(funding, AMOUNT_FRACTION_DIGITS);
  }

  /** {@code amount} valued in base, or null when the leg's rate is unknown on that date. */
  private BigDecimal toBase(
      BigDecimal amount, String currencyCode, String baseCurrency, LocalDate date) {
    if (currencyCode.equals(baseCurrency)) {
      return amount;
    }
    return exchangeRateService.rateAsOf(currencyCode, date).map(amount::multiply).orElse(null);
  }

  /**
   * A base-currency {@code amount} valued in {@code currencyCode}, or null when its rate is
   * unknown.
   */
  private BigDecimal fromBase(
      BigDecimal amount, String currencyCode, String baseCurrency, LocalDate date) {
    if (currencyCode.equals(baseCurrency)) {
      return amount;
    }
    return exchangeRateService
        .rateAsOf(currencyCode, date)
        .filter(rate -> rate.signum() != 0)
        .map(rate -> amount.divide(rate, RATE_SCALE, RoundingMode.HALF_UP))
        .orElse(null);
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
