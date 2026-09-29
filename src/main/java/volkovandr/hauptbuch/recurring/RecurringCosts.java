package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
import volkovandr.hauptbuch.recurring.RecurringCostSummary.Line;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * What the live templates cost (data-model §14.4, recurring sub-plan slice g): each template's
 * figures, and the Recurring cost summary over all of them. Both are schedule math over the stored
 * lines ({@link Schedule#perYear}), valued in base at today's rate; neither reads a booked posting.
 * A line's signed contribution to the funding leg (negative = an outflow) is read as the split
 * panel reads it.
 */
@Service
class RecurringCosts {

  private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

  /** How the summary classifies a non-funding leg. */
  private enum Leg {
    EXPENSE,
    INCOME,
    TRANSFER
  }

  private final RecurringTemplateRepository repository;
  private final AccountService accountService;
  private final SettingsService settingsService;
  private final ExchangeRateService exchangeRateService;
  private final Clock clock;

  RecurringCosts(
      RecurringTemplateRepository repository,
      AccountService accountService,
      SettingsService settingsService,
      ExchangeRateService exchangeRateService,
      Clock clock) {
    this.repository = repository;
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.exchangeRateService = exchangeRateService;
    this.clock = clock;
  }

  /** Each live template's figures, keyed by template id. */
  Map<Long, RecurringFigures> figures() {
    String base = settingsService.baseCurrency().orElse(null);
    Map<Long, RecurringFigures> figures = new HashMap<>();
    for (RecurringTemplate template : repository.findLive()) {
      String currency = currencyOf(template, base);
      BigDecimal net = BigDecimal.ZERO;
      for (RecurringTemplateLine line : repository.findLines(template.recurringTemplateId())) {
        net = net.add(contribution(line));
      }
      figures.put(
          template.recurringTemplateId(), figuresOf(template.schedule(), net, currency, base));
    }
    return figures;
  }

  /** The Recurring cost summary over the live templates that have not ended, in base. */
  RecurringCostSummary summary() {
    String base = settingsService.baseCurrency().orElse(null);
    Map<String, BigDecimal> expenses = new TreeMap<>();
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal transfer = BigDecimal.ZERO;
    List<String> leftOut = new ArrayList<>();
    LocalDate today = LocalDate.now(clock);
    for (RecurringTemplate template : repository.findLive()) {
      if (template.endDate() != null && template.endDate().isBefore(today)) {
        continue; // ended: it no longer costs anything per month or per year
      }
      BigDecimal rate = rate(currencyOf(template, base), base);
      if (rate == null) {
        leftOut.add(template.name());
        continue;
      }
      for (RecurringTemplateLine line : repository.findLines(template.recurringTemplateId())) {
        BigDecimal perYear = template.schedule().perYear(contribution(line)).multiply(rate);
        Leg leg = legOf(line);
        if (leg == Leg.EXPENSE) {
          expenses.merge(topLevelName(line.accountId()), perYear.negate(), BigDecimal::add);
        } else if (leg == Leg.INCOME) {
          income = income.add(perYear);
        } else {
          transfer = transfer.add(perYear.negate());
        }
      }
    }
    BigDecimal expenseTotal = expenses.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    List<Line> expenseLines = new ArrayList<>();
    expenses.forEach((name, perYear) -> expenseLines.add(line(name, perYear, base)));
    return new RecurringCostSummary(
        expenseLines,
        line("Expenses", expenseTotal, base),
        line("Income", income, base),
        line("Transfers", transfer, base),
        line("Net", income.subtract(expenseTotal).subtract(transfer), base),
        leftOut);
  }

  /** The currency a template's lines are in, and how it converts to base today. */
  private record Valuation(String currency, String base, BigDecimal rate) {

    String display(BigDecimal amount) {
      String nativeText = MoneyFormat.display(MoneyFactory.of(amount, currency), base);
      if (currency.equals(base) || rate == null) {
        return nativeText;
      }
      return nativeText
          + " ("
          + MoneyFormat.display(MoneyFactory.of(amount.multiply(rate), base), base)
          + ")";
    }
  }

  private RecurringFigures figuresOf(
      Schedule schedule, BigDecimal net, String currency, String base) {
    Valuation valuation = new Valuation(currency, base, rate(currency, base));
    String perMonth = valuation.display(schedule.perMonth(net));
    String perYear = valuation.display(schedule.perYear(net));
    if (schedule.endDate() == null) {
      return new RecurringFigures(perMonth, perYear, null, null, null);
    }
    LocalDate today = LocalDate.now(clock);
    BigDecimal already =
        net.multiply(
            BigDecimal.valueOf(
                schedule.occurrencesBetween(schedule.startDate().minusDays(1), today).size()));
    BigDecimal yetToPay =
        net.multiply(
            BigDecimal.valueOf(schedule.occurrencesBetween(today, schedule.endDate()).size()));
    return new RecurringFigures(
        perMonth,
        perYear,
        valuation.display(already),
        valuation.display(yetToPay),
        valuation.display(already.add(yetToPay)));
  }

  private static Line line(String label, BigDecimal perYear, String base) {
    return new Line(
        label,
        MoneyFormat.display(
            MoneyFactory.of(perYear.divide(MONTHS_PER_YEAR, MathContext.DECIMAL64), base), base),
        MoneyFormat.display(MoneyFactory.of(perYear, base), base));
  }

  /**
   * Units of base per unit of {@code currency} today: 1 for base itself, else the latest rate on or
   * before today, or null when none is known.
   */
  private BigDecimal rate(String currency, String base) {
    if (currency.equals(base)) {
      return BigDecimal.ONE;
    }
    return exchangeRateService.rateAsOf(currency, LocalDate.now(clock)).orElse(null);
  }

  /**
   * The currency the lines are in: the spending currency, else the funding account's, else (a
   * person-funded template with no currency picked) base, as the recurring page's amount shows it.
   */
  private String currencyOf(RecurringTemplate template, String base) {
    if (template.spendingCurrencyCode() != null) {
      return template.spendingCurrencyCode();
    }
    if (template.accountId() != null) {
      return account(template.accountId()).currencyCode();
    }
    return base;
  }

  private BigDecimal contribution(RecurringTemplateLine line) {
    String amount = SplitLineAmounts.formatSignedAmount(line.amount());
    if (line.personId() != null || line.transferDirection() != null) {
      return SplitLineAmounts.lenientContribution(
          amount, null, line.transferDirection(), line.personDirection());
    }
    return SplitLineAmounts.lenientContribution(
        amount, account(line.accountId()).type(), null, null);
  }

  /** A person or transfer line moves money to someone or somewhere of the book's own. */
  private Leg legOf(RecurringTemplateLine line) {
    if (line.personId() != null || line.transferDirection() != null) {
      return Leg.TRANSFER;
    }
    return "income".equals(account(line.accountId()).type()) ? Leg.INCOME : Leg.EXPENSE;
  }

  private String topLevelName(long accountId) {
    Account account = account(accountId);
    while (account.parentId() != null) {
      account = account(account.parentId());
    }
    return account.name();
  }

  private Account account(long accountId) {
    return accountService
        .findById(accountId)
        .orElseThrow(() -> new IllegalStateException("Template references missing account"));
  }
}
