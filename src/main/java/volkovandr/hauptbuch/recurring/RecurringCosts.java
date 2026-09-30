package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * What the live templates cost (data-model §14.4, recurring sub-plan slice g): each template's
 * figures, and the Recurring cost summary over all of them. Both are schedule math over the stored
 * lines ({@link Schedule#perYear}), valued in base at today's rate; neither reads a booked posting.
 * A line's signed contribution to the funding leg (negative = an outflow) is read as the split
 * panel reads it.
 */
@Service
class RecurringCosts {

  private static final String INCOME = "income";

  private final RecurringTemplateRepository repository;
  private final AccountService accountService;
  private final SettingsService settingsService;
  private final ExchangeRateService exchangeRateService;
  private final PersonService personService;
  private final Clock clock;

  RecurringCosts(
      RecurringTemplateRepository repository,
      AccountService accountService,
      SettingsService settingsService,
      ExchangeRateService exchangeRateService,
      PersonService personService,
      Clock clock) {
    this.repository = repository;
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.exchangeRateService = exchangeRateService;
    this.personService = personService;
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

  /**
   * The recurring page's summary over the live templates that have not ended: the Recurring cost
   * table (expenses and income by category, in base), the Transfers between own accounts, and the
   * People's debts. Each leg's posting is the opposite of its contribution to the funding leg, and
   * the funding leg's is the lines' net, as the booked entry's would be.
   */
  RecurringCostSummary summary() {
    String base = settingsService.baseCurrency().orElse(null);
    Tables tables = new Tables();
    List<String> leftOut = new ArrayList<>();
    LocalDate today = LocalDate.now(clock);
    for (RecurringTemplate template : repository.findLive()) {
      if (template.endDate() != null && template.endDate().isBefore(today)) {
        continue; // ended: it no longer costs anything per month or per year
      }
      Valuation valuation = valuationOf(template, base);
      if (valuation.rate() == null) {
        leftOut.add(template.name());
      } else {
        addTemplate(template, valuation, tables);
      }
    }
    return tables.summary(base, leftOut);
  }

  /** The summary's three tables as they fill up. */
  private static final class Tables {

    private final CostTree expenses = new CostTree("Expenses");
    private final CostTree income = new CostTree("Income");
    private final TransferTable transfers = new TransferTable();
    private final PeopleTable people = new PeopleTable();

    RecurringCostSummary summary(String base, List<String> leftOut) {
      return new RecurringCostSummary(
          expenses.breakdown(base),
          expenses.total(base),
          income.breakdown(base),
          income.total(base),
          Valuation.inBase(base)
              .line("Net", 0, null, income.sumPerYear().subtract(expenses.sumPerYear())),
          transfers.lines(),
          transfers.total(base),
          people.lines(),
          people.total(base),
          leftOut);
    }
  }

  /** Adds each leg of {@code template}, the funding leg's being the lines' net. */
  private void addTemplate(RecurringTemplate template, Valuation valuation, Tables tables) {
    Schedule schedule = template.schedule();
    BigDecimal fundingNet = BigDecimal.ZERO;
    for (RecurringTemplateLine line : repository.findLines(template.recurringTemplateId())) {
      BigDecimal contribution = contribution(line);
      fundingNet = fundingNet.add(contribution);
      addLine(template, line, schedule.perYear(contribution.negate()), valuation, tables);
    }
    if (template.personId() != null) {
      tables.people.add(
          personName(template.personId()),
          template.name(),
          schedule.perYear(fundingNet),
          valuation);
    }
  }

  /** Adds a line's leg, {@code posting} per year in the template's currency (+ = debit). */
  private void addLine(
      RecurringTemplate template,
      RecurringTemplateLine line,
      BigDecimal posting,
      Valuation valuation,
      Tables tables) {
    if (line.personId() != null) {
      tables.people.add(personName(line.personId()), template.name(), posting, valuation);
    } else if (line.transferDirection() == null) {
      // An expense is a debit, income a credit: both count positive in their section.
      BigDecimal perYear = valuation.toBase(posting);
      if (INCOME.equals(account(line.accountId()).type())) {
        tables.income.add(accountPath(line.accountId()), perYear.negate());
      } else {
        tables.expenses.add(accountPath(line.accountId()), perYear);
      }
    } else if (template.accountId() != null) {
      tables.transfers.add(
          ownAccountLabel(template.accountId()),
          ownAccountLabel(line.accountId()),
          posting,
          valuation);
    }
    // A transfer a person funds is the person's debt alone: the People table shows it.
  }

  private Valuation valuationOf(RecurringTemplate template, String base) {
    String currency = currencyOf(template, base);
    return new Valuation(currency, base, rate(currency, base));
  }

  private RecurringFigures figuresOf(
      Schedule schedule, BigDecimal net, String currency, String base) {
    Valuation valuation = new Valuation(currency, base, rate(currency, base));
    String perMonth = valuation.display(schedule.perMonth(net));
    String perYear = valuation.display(schedule.perYear(net));
    LocalDate today = LocalDate.now(clock);
    BigDecimal already =
        net.multiply(
            BigDecimal.valueOf(
                schedule.occurrencesBetween(schedule.startDate().minusDays(1), today).size()));
    if (schedule.endDate() == null) {
      return new RecurringFigures(perMonth, perYear, valuation.display(already), null, null);
    }
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

  private String personName(long personId) {
    return personService.findById(personId).map(Person::name).orElse("Unknown person");
  }

  private String ownAccountLabel(long accountId) {
    return accountService.ownAccountEntryLabel(account(accountId));
  }

  /**
   * The account names from the top level down to the line's account. A per-currency category leaf
   * is hidden everywhere (data-model §6.5), so it adds to its parent category rather than a row.
   */
  private List<String> accountPath(long accountId) {
    List<String> path = new ArrayList<>();
    Account account = account(accountId);
    while (true) {
      if (!account.currencyLeaf()) {
        path.add(0, account.name());
      }
      if (account.parentId() == null) {
        return path;
      }
      account = account(account.parentId());
    }
  }

  private Account account(long accountId) {
    return accountService
        .findById(accountId)
        .orElseThrow(() -> new IllegalStateException("Template references missing account"));
  }
}
