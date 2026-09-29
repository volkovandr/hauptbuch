package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.debts.PersonTarget;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.ledger.TransferTarget;
import volkovandr.hauptbuch.operations.SplitCurrencyService;
import volkovandr.hauptbuch.operations.SplitForm;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;
import volkovandr.hauptbuch.shared.MoneyFactory;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The read side of the recurring page (recurring sub-plan slice b): the template list, and a stored
 * template turned back into the split panel's form for the editor. It reads each stored line the
 * way {@code SplitEditService} reads a booked split, so a template reopens exactly as it was
 * entered.
 */
@Service
class RecurringTemplateViews {

  private static final int NEXT_DATES = 3;
  private static final int FRACTION_DIGITS = 2;

  private final RecurringTemplateRepository repository;
  private final AccountService accountService;
  private final PersonService personService;
  private final PayeeService payeeService;
  private final SettingsService settingsService;
  private final SplitCurrencyService splitCurrencyService;
  private final Clock clock;

  RecurringTemplateViews(
      RecurringTemplateRepository repository,
      AccountService accountService,
      PersonService personService,
      PayeeService payeeService,
      SettingsService settingsService,
      SplitCurrencyService splitCurrencyService,
      Clock clock) {
    this.repository = repository;
    this.accountService = accountService;
    this.personService = personService;
    this.payeeService = payeeService;
    this.settingsService = settingsService;
    this.splitCurrencyService = splitCurrencyService;
    this.clock = clock;
  }

  /** The live templates, by name, with their next three occurrence dates from today. */
  List<RecurringTemplateRow> rows() {
    LocalDate today = LocalDate.now(clock);
    String base = settingsService.baseCurrency().orElse(null);
    List<RecurringTemplateRow> rows = new ArrayList<>();
    for (RecurringTemplate template : repository.findLive()) {
      Schedule schedule = template.schedule();
      BigDecimal net = BigDecimal.ZERO;
      for (RecurringTemplateLine line : repository.findLines(template.recurringTemplateId())) {
        net = net.add(read(line).contribution());
      }
      rows.add(
          new RecurringTemplateRow(
              template.recurringTemplateId(),
              template.name(),
              CadenceWords.of(schedule),
              MoneyFormat.display(MoneyFactory.of(net, currencyOf(template, base)), base),
              fundingLabel(template),
              RecurringScheduleForm.REVIEW.equals(template.confirmation()) ? "Review" : "Automatic",
              leadTime(template.leadDays()),
              schedule.nextOccurrences(today.minusDays(1), NEXT_DATES),
              template.managementUrl()));
    }
    return rows;
  }

  /** A new template's editor: one blank line, starting today, funded by {@code accountId}. */
  RecurringEditor blank(Long accountId) {
    SplitForm split = SplitForm.blank(LocalDate.now(clock), accountId);
    return new RecurringEditor(split, RecurringScheduleForm.blank());
  }

  /**
   * A stored template's editor: the entry back in the split panel's form, and the schedule. The
   * cross-currency totals are proposed afresh for the start date, since a template stores none.
   *
   * @throws IllegalArgumentException if there is no live template with that id
   */
  RecurringEditor load(long recurringTemplateId) {
    RecurringTemplate template =
        repository
            .findById(recurringTemplateId)
            .filter(t -> t.deletedAt() == null)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No live recurring template with id " + recurringTemplateId));
    List<String> categoryText = new ArrayList<>();
    List<String> categoryId = new ArrayList<>();
    List<String> categoryType = new ArrayList<>();
    List<String> transferDirection = new ArrayList<>();
    List<String> personName = new ArrayList<>();
    List<String> personDirection = new ArrayList<>();
    List<String> amount = new ArrayList<>();
    List<String> note = new ArrayList<>();
    List<List<Long>> lineTagIds = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (RecurringTemplateLine stored : repository.findLines(recurringTemplateId)) {
      ReadLine line = read(stored);
      categoryText.add(line.categoryText());
      categoryId.add(line.categoryId());
      categoryType.add(line.categoryType());
      transferDirection.add(line.transferDirection());
      personName.add(line.personName());
      personDirection.add(line.personDirection());
      amount.add(line.amount());
      note.add(stored.note() == null ? "" : stored.note());
      lineTagIds.add(repository.findLineTagIds(stored.recurringTemplateLineId()));
      net = net.add(line.contribution());
    }
    String fundingPerson = template.personId() == null ? "" : personName(template.personId());
    SplitForm split =
        new SplitForm(
            null,
            template.startDate(),
            template.accountId(),
            fundingPerson,
            template.fundingPersonDirection() == null ? "" : template.fundingPersonDirection(),
            "",
            template.payeeId() == null
                ? ""
                : payeeService.entryValueFor(template.payeeId()).orElse(""),
            template.note() == null ? "" : template.note(),
            MoneyFormat.number(net.abs(), FRACTION_DIGITS),
            template.spendingCurrencyCode(),
            "",
            "",
            categoryText,
            categoryId,
            categoryType,
            transferDirection,
            personName,
            personDirection,
            Collections.nCopies(personName.size(), ""),
            amount,
            note,
            repository.findTagIds(recurringTemplateId),
            lineTagIds,
            null,
            null,
            null,
            null,
            null,
            false);
    return new RecurringEditor(
        splitCurrencyService.withProposedTotals(split), scheduleOf(template));
  }

  private static RecurringScheduleForm scheduleOf(RecurringTemplate template) {
    return new RecurringScheduleForm(
        template.recurringTemplateId(),
        template.name(),
        String.valueOf(template.cadenceN()),
        template.cadenceUnit(),
        template.endDate() == null
            ? RecurringScheduleForm.END_NONE
            : RecurringScheduleForm.END_DATE,
        template.endDate() == null ? "" : template.endDate().toString(),
        "",
        String.valueOf(template.leadDays()),
        template.confirmation(),
        template.managementUrl() == null ? "" : template.managementUrl(),
        "",
        "");
  }

  /**
   * One stored line as the split panel shows it: a category by its semantic name and type, a
   * transfer as {@code To → account}, a person as {@code for}/{@code by} their name. Also its
   * signed contribution to the funding leg (negative = an outflow).
   */
  private ReadLine read(RecurringTemplateLine line) {
    String amountText = SplitLineAmounts.formatSignedAmount(line.amount());
    if (line.personId() != null) {
      String name = personName(line.personId());
      return new ReadLine(
          PersonTarget.option(PersonTarget.Direction.valueOf(line.personDirection()), name),
          "",
          "",
          "",
          name,
          line.personDirection(),
          amountText,
          SplitLineAmounts.lenientContribution(amountText, null, null, line.personDirection()));
    }
    Account account = account(line.accountId());
    if (line.transferDirection() != null) {
      return new ReadLine(
          TransferTarget.option(
              TransferTarget.Direction.valueOf(line.transferDirection()),
              accountService.ownAccountEntryLabel(account)),
          String.valueOf(account.accountId()),
          "",
          line.transferDirection(),
          "",
          "",
          amountText,
          SplitLineAmounts.lenientContribution(amountText, null, line.transferDirection(), null));
    }
    return new ReadLine(
        account.name(),
        String.valueOf(account.accountId()),
        account.type(),
        "",
        "",
        "",
        amountText,
        SplitLineAmounts.lenientContribution(amountText, account.type(), null, null));
  }

  private record ReadLine(
      String categoryText,
      String categoryId,
      String categoryType,
      String transferDirection,
      String personName,
      String personDirection,
      String amount,
      BigDecimal contribution) {}

  /** The funding account's label, or the funding person as {@code for}/{@code by} their name. */
  private String fundingLabel(RecurringTemplate template) {
    if (template.personId() != null) {
      return PersonTarget.option(
          PersonTarget.Direction.valueOf(template.fundingPersonDirection()),
          personName(template.personId()));
    }
    return accountService.ownAccountEntryLabel(account(template.accountId()));
  }

  /**
   * The currency the amount is shown in: the lines' spending currency, else the funding account's,
   * else (a person-funded template with no currency picked) the book's base currency.
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

  private static String leadTime(int leadDays) {
    if (leadDays == 0) {
      return "on the day";
    }
    return leadDays == 1 ? "1 day ahead" : leadDays + " days ahead";
  }

  private Account account(long accountId) {
    return accountService
        .findById(accountId)
        .orElseThrow(() -> new IllegalStateException("Template references missing account"));
  }

  private String personName(long personId) {
    return personService.findById(personId).map(Person::name).orElse("");
  }
}
