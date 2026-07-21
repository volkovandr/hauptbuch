package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joda.money.Money;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.debts.CurrencyBalance;
import volkovandr.hauptbuch.debts.PersonBalanceSummary;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.PeopleOverview.CurrencyLine;
import volkovandr.hauptbuch.operations.PeopleOverview.PersonRow;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Builds the People-balances render model (data-model §7, plan stage 8d): every live person's
 * per-currency debt position from {@code debts}, plus a supplementary base-currency total valued at
 * today's rates from {@code ledger}. Lives in {@code operations} because it must reach both modules
 * and {@code debts} may not depend on {@code ledger} (the module edge already runs the other way,
 * from the 8c register name resolution) — the same reason the entry dock's cross-module controllers
 * live here (plan stage 7 boundary note).
 *
 * <p>The base total is a gloss, never the truth: per-currency figures are the settlement basis
 * (data-model §7). It is shown only when every currency the person holds could be valued — a
 * missing rate suppresses the gloss rather than reporting a partial number as if whole.
 */
@Service
class PeopleOverviewService {

  private final PersonService personService;
  private final SettingsService settingsService;
  private final ExchangeRateService exchangeRateService;

  PeopleOverviewService(
      PersonService personService,
      SettingsService settingsService,
      ExchangeRateService exchangeRateService) {
    this.personService = personService;
    this.settingsService = settingsService;
    this.exchangeRateService = exchangeRateService;
  }

  /** The whole People roster with balances, ordered by name (as {@link PersonService} orders). */
  PeopleOverview overview() {
    Optional<String> baseCurrency = settingsService.baseCurrency();
    LocalDate today = LocalDate.now();
    List<PersonRow> rows = new ArrayList<>();
    for (PersonBalanceSummary summary : personService.balanceSummaries()) {
      rows.add(row(summary, baseCurrency, today));
    }
    return new PeopleOverview(rows, baseCurrency.orElse(""));
  }

  private PersonRow row(
      PersonBalanceSummary summary, Optional<String> baseCurrency, LocalDate today) {
    String base = baseCurrency.orElse("");
    List<CurrencyLine> lines = new ArrayList<>();
    for (CurrencyBalance balance : summary.balances()) {
      lines.add(line(summary.name(), balance, base));
    }
    BaseTotal baseTotal = baseTotal(summary.balances(), baseCurrency, today);
    // The base gloss earns its place only when it says something the per-currency figures don't: a
    // person holding some non-base currency, whose every currency could be valued. An all-base
    // position (the total just restates the one figure), a settled person, or a missing rate all
    // suppress it — redundant or partial, never shown.
    boolean hasNonBase = summary.balances().stream().anyMatch(b -> !b.currencyCode().equals(base));
    boolean shown = hasNonBase && baseTotal.complete();
    return new PersonRow(
        summary.personId(),
        summary.name(),
        lines,
        shown,
        shown ? MoneyFormat.display(MoneyFactory.of(baseTotal.amount(), base), base) : "",
        baseTotal.amount().signum() < 0,
        summary.accountIds());
  }

  /** One currency's collapsed figure and its directional sentence. */
  private CurrencyLine line(String personName, CurrencyBalance balance, String base) {
    Money signed = MoneyFactory.of(balance.signedBalance(), balance.currencyCode());
    boolean negative = balance.signedBalance().signum() < 0;
    String magnitude = MoneyFormat.display(signed.abs(), base);
    String direction = negative ? "You owe " + magnitude : personName + " owes you " + magnitude;
    return new CurrencyLine(
        balance.currencyCode(), MoneyFormat.display(signed, base), negative, direction);
  }

  /**
   * The base-currency total: Σ (native balance × rate@today), valuing a base-currency leg at 1 and
   * a non-base leg at its carry-forward rate. {@code complete} is false when the book has no base
   * currency or any non-base currency lacks a rate — either way the gloss is suppressed rather than
   * shown partial.
   */
  private BaseTotal baseTotal(
      List<CurrencyBalance> balances, Optional<String> baseCurrency, LocalDate today) {
    if (baseCurrency.isEmpty()) {
      return new BaseTotal(BigDecimal.ZERO, false);
    }
    String base = baseCurrency.get();
    BigDecimal total = BigDecimal.ZERO;
    boolean complete = true;
    for (CurrencyBalance balance : balances) {
      if (balance.currencyCode().equals(base)) {
        total = total.add(balance.signedBalance());
        continue;
      }
      Optional<BigDecimal> rate = exchangeRateService.rateAsOf(balance.currencyCode(), today);
      if (rate.isEmpty()) {
        complete = false;
      } else {
        total = total.add(balance.signedBalance().multiply(rate.get()));
      }
    }
    return new BaseTotal(total, complete);
  }

  /** The base-currency total and whether every leg contributing to it could be valued. */
  private record BaseTotal(BigDecimal amount, boolean complete) {}
}
