package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.SplitForm;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The rate a recurring template's typed cross-currency totals state (data-model §3.7, §14.1). A
 * template stores no totals, so what the operator typed would otherwise be forgotten and every
 * occurrence would ask the rate table instead; this records it against the start date, where the
 * occurrences' proposals find it by carry-forward. Totals left blank are proposals from the rate
 * table already, so they state nothing new and record nothing.
 */
@Component
class RecurringEnteredRate {

  private final AccountService accountService;
  private final SettingsService settingsService;
  private final ExchangeRateService exchangeRateService;

  RecurringEnteredRate(
      AccountService accountService,
      SettingsService settingsService,
      ExchangeRateService exchangeRateService) {
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.exchangeRateService = exchangeRateService;
  }

  /**
   * Record the rate(s) the typed totals of {@code split} state on {@code date}: the funding total
   * against the spending total when one side is base, and each against the base total when neither
   * is. Does nothing for a single-currency entry or when the needed totals were not typed.
   */
  void record(LocalDate date, SplitForm split, String spendingCurrency) {
    if (split.accountId() == null || spendingCurrency == null) {
      return;
    }
    String funding =
        accountService.findById(split.accountId()).map(Account::currencyCode).orElse(null);
    String base = settingsService.baseCurrency().orElse(null);
    BigDecimal spendingTotal = magnitude(split.total());
    BigDecimal fundingTotal = magnitude(split.fundingTotal());
    if (funding == null
        || base == null
        || funding.equals(spendingCurrency)
        || fundingTotal == null) {
      return;
    }
    if (funding.equals(base)) {
      exchangeRateService.recordEnteredRate(date, spendingCurrency, spendingTotal, fundingTotal);
    } else if (spendingCurrency.equals(base)) {
      exchangeRateService.recordEnteredRate(date, funding, fundingTotal, spendingTotal);
    } else {
      BigDecimal baseTotal = magnitude(split.baseTotal());
      exchangeRateService.recordEnteredRate(date, spendingCurrency, spendingTotal, baseTotal);
      exchangeRateService.recordEnteredRate(date, funding, fundingTotal, baseTotal);
    }
  }

  /** The typed figure's magnitude, or null when blank. */
  private static BigDecimal magnitude(String text) {
    return text == null || text.isBlank() ? null : MoneyFormat.parse(text).abs();
  }
}
