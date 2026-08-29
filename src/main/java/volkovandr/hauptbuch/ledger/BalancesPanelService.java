package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joda.money.Money;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.ledger.BalancesPanel.Row;
import volkovandr.hauptbuch.ledger.repository.PinnedBalance;
import volkovandr.hauptbuch.ledger.repository.PinnedBalanceRepository;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Builds the landing page's Balances-panel model (CONTEXT.md "Balances panel", issue
 * landing-page/01) from the pinned-account balances and the book's rates. Structured like {@code
 * PeopleOverviewService}: the native figures come straight from the repository, and only the
 * base-currency bracket and total are valued here — mark-to-market at today's rate (data-model §9).
 *
 * <p>Stays in {@code ledger} alongside {@link LandingController}: it needs the {@code accounts}
 * table (read through {@link PinnedBalanceRepository}, already a {@code ledger} dependency), rates,
 * and the base currency — none of {@code debts}/{@code receipts} — so no module move.
 *
 * <p>Computed server-side in the {@code /} render, not lazy-loaded like the sibling tracking-stats
 * line (issue landing-page/01 brief: a model attribute, compute on the fly, cache only if measured
 * slow). One grouped query plus one rate lookup per non-base pinned currency — a handful of pinned
 * accounts, not the whole ledger.
 */
@Service
class BalancesPanelService {

  private final PinnedBalanceRepository pinnedBalanceRepository;
  private final SettingsService settingsService;
  private final ExchangeRateService exchangeRateService;

  BalancesPanelService(
      PinnedBalanceRepository pinnedBalanceRepository,
      SettingsService settingsService,
      ExchangeRateService exchangeRateService) {
    this.pinnedBalanceRepository = pinnedBalanceRepository;
    this.settingsService = settingsService;
    this.exchangeRateService = exchangeRateService;
  }

  /**
   * The panel model, or empty when it should not render at all: no base currency yet, or nothing
   * pinned that survives the closed/deleted filter.
   */
  Optional<BalancesPanel> current() {
    Optional<String> baseCurrency = settingsService.baseCurrency();
    if (baseCurrency.isEmpty()) {
      return Optional.empty();
    }
    List<PinnedBalance> pinned = pinnedBalanceRepository.findPinnedBalances();
    if (pinned.isEmpty()) {
      return Optional.empty();
    }

    String base = baseCurrency.get();
    LocalDate today = LocalDate.now();
    List<Row> rows = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    boolean everyRowValued = true;

    for (PinnedBalance account : pinned) {
      Money nativeBalance = MoneyFactory.of(account.balance(), account.currencyCode());
      String nativeText = MoneyFormat.display(nativeBalance, base);
      boolean negative = account.balance().signum() < 0;

      String amount;
      if (account.currencyCode().equals(base)) {
        amount = nativeText;
        total = total.add(account.balance());
      } else {
        Optional<BigDecimal> rate = exchangeRateService.rateAsOf(account.currencyCode(), today);
        if (rate.isPresent()) {
          BigDecimal baseValue = account.balance().multiply(rate.get());
          total = total.add(baseValue);
          String bracket = MoneyFormat.display(MoneyFactory.of(baseValue, base), base);
          amount = nativeText + " (" + bracket + ")";
        } else {
          everyRowValued = false;
          amount = nativeText + " (—)";
        }
      }
      rows.add(new Row(account.accountId(), account.hue(), account.name(), amount, negative));
    }

    boolean totalShown = pinned.size() >= 2 && everyRowValued;
    String totalText = totalShown ? MoneyFormat.display(MoneyFactory.of(total, base), base) : "";
    return Optional.of(new BalancesPanel(rows, totalShown, totalText, base));
  }
}
