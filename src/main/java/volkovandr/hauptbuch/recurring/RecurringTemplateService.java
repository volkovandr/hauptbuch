package volkovandr.hauptbuch.recurring;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.operations.DockSplitService;
import volkovandr.hauptbuch.operations.SplitCurrencyService;
import volkovandr.hauptbuch.operations.SplitEntry;
import volkovandr.hauptbuch.operations.SplitForm;
import volkovandr.hauptbuch.operations.SplitFormBinder;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
import volkovandr.hauptbuch.operations.SplitLineDraft;
import volkovandr.hauptbuch.operations.TransactionCurrencyResolver;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Saves and deletes recurring templates (data-model §14, recurring sub-plan slices b and c). A save
 * takes the split panel's form plus the schedule block ({@link RecurringScheduleParser} parses the
 * latter), and refuses whatever the dock would refuse: it dry-runs the entry through {@link
 * DockSplitService#validate} on the start date before storing anything. It then runs the booking
 * for that template, in the same transaction; an occurrence that cannot book rolls back only the
 * run and is recorded on the template (slice f), so the save itself stands. Saving an existing
 * template rebooks it (data-model §14.3): its untouched future pending rows are replaced under the
 * new settings.
 */
@Service
@SuppressWarnings("PMD.CouplingBetweenObjects")
class RecurringTemplateService {

  private static final Logger LOG = LoggerFactory.getLogger(RecurringTemplateService.class);

  private final RecurringTemplateRepository repository;
  private final DockSplitService dockSplitService;
  private final SplitCurrencyService splitCurrencyService;
  private final PayeeService payeeService;
  private final PersonProvisioningService personProvisioningService;
  private final TransactionCurrencyResolver transactionCurrencyResolver;
  private final RecurringBookingService bookingService;
  private final RecurringBookingRunner runner;
  private final RecurringEnteredRate enteredRate;
  private final Clock clock;

  // ExcessiveParameterList: a Spring-injected constructor; each collaborator is a distinct step of
  // the save
  @SuppressWarnings("PMD.ExcessiveParameterList")
  RecurringTemplateService(
      RecurringTemplateRepository repository,
      DockSplitService dockSplitService,
      SplitCurrencyService splitCurrencyService,
      PayeeService payeeService,
      PersonProvisioningService personProvisioningService,
      TransactionCurrencyResolver transactionCurrencyResolver,
      RecurringBookingService bookingService,
      RecurringBookingRunner runner,
      RecurringEnteredRate enteredRate,
      Clock clock) {
    this.repository = repository;
    this.dockSplitService = dockSplitService;
    this.splitCurrencyService = splitCurrencyService;
    this.payeeService = payeeService;
    this.personProvisioningService = personProvisioningService;
    this.transactionCurrencyResolver = transactionCurrencyResolver;
    this.bookingService = bookingService;
    this.runner = runner;
    this.enteredRate = enteredRate;
    this.clock = clock;
  }

  /**
   * How many occurrences of a new template fall before today, for the save to ask whether to book
   * them (data-model §14.3). Zero for an existing template, and for a start today or later: an
   * occurrence already inside the lead time books without asking.
   *
   * @throws IllegalArgumentException if the schedule is incomplete, carrying the message to show
   */
  int pastOccurrences(RecurringScheduleForm schedule, SplitForm split) {
    RecurringTemplateDraft draft = RecurringScheduleParser.parse(schedule, split.date());
    LocalDate today = LocalDate.now(clock);
    if (schedule.recurringTemplateId() != null || !draft.startDate().isBefore(today)) {
      return 0;
    }
    return draft
        .schedule()
        .occurrencesBetween(draft.startDate().minusDays(1), today.minusDays(1))
        .size();
  }

  /**
   * How many of an existing template's pending rows the end date in {@code schedule} newly cuts
   * off, for the save to ask whether to keep them (data-model §14.3): those after the new end and
   * not after the stored one. Zero for a new template and for no end.
   *
   * @throws IllegalArgumentException if the schedule is incomplete, carrying the message to show
   */
  int cutOffPending(RecurringScheduleForm schedule, SplitForm split) {
    LocalDate end = RecurringScheduleParser.parse(schedule, split.date()).endDate();
    Long id = schedule.recurringTemplateId();
    if (id == null || end == null) {
      return 0;
    }
    LocalDate storedEnd = repository.findById(id).map(RecurringTemplate::endDate).orElse(null);
    return bookingService.pendingRowsCutOff(id, end, storedEnd);
  }

  /** How many pending rows a template has, for its delete to ask whether to keep them. */
  int pendingRowCount(long recurringTemplateId) {
    return bookingService.pendingRowCount(recurringTemplateId);
  }

  /**
   * Validate and store a template, then book what it has due (data-model §14.3). A new one starts
   * its cursor the day before its start when the operator chose to book its past occurrences, else
   * at yesterday, so nothing past is booked. An edit is rebooked: its future pending rows are
   * replaced, and pending rows the end date cuts off are kept or removed as the operator answered.
   * A booking that fails is recorded on the template, and the template is saved all the same.
   *
   * @return the template's id
   * @throws IllegalArgumentException if the schedule is incomplete or the dock refuses the entry,
   *     carrying the message to show
   * @throws IllegalStateException if the dock refuses the entry for want of a base currency
   */
  @Transactional
  long save(RecurringScheduleForm schedule, SplitForm split) {
    RecurringTemplateDraft unresolved = RecurringScheduleParser.parse(schedule, split.date());
    if (split.accountId() == null && !split.hasFundingPerson()) {
      throw new IllegalArgumentException("An account or person is required");
    }
    List<SplitLineDraft> lines = SplitFormBinder.linesOf(split);
    String spending = spendingCurrency(split);
    // The payee and the people are resolved for real before the dry run, so the dry run finds them
    // and logs no "created" line for rows it then rolls back. A refused save rolls them back too.
    RecurringTemplateDraft draft = withEntry(unresolved, split, lines, spending);
    dockSplitService.validate(entryOf(split, lines, spending));
    // The dry run keeps nothing, so the rate the typed totals state is recorded for real here,
    // where
    // the occurrences' proposals will find it.
    enteredRate.record(draft.startDate(), split, spending);

    Long id = schedule.recurringTemplateId();
    if (id == null) {
      LocalDate bookedThrough =
          RecurringScheduleForm.BOOK_PAST.equals(schedule.pastOccurrences())
              ? draft.startDate().minusDays(1)
              : LocalDate.now(clock).minusDays(1);
      long created = repository.insert(draft, bookedThrough);
      LOG.info("Recurring template created: id={}, name={}", created, draft.name());
      runner.book(created);
      return created;
    }
    if (repository.update(id, draft) == 0) {
      throw new IllegalArgumentException("No live recurring template with id " + id);
    }
    LOG.debug("Recurring template saved: id={}", id);
    // Unasked, no row is newly cut off: rows already beyond the end were kept by an earlier answer.
    PendingRows answer = schedule.pendingRowsAnswer();
    bookingService.rewind(id, answer == null ? PendingRows.KEEP_ALL : answer);
    runner.book(id);
    return id;
  }

  /**
   * Soft-delete a template and remove its pending rows as the operator answered (data-model §14.3).
   * Confirmed transactions it booked always stay.
   *
   * @throws IllegalArgumentException if there is no live template with that id
   */
  @Transactional
  void delete(long recurringTemplateId, PendingRows answer) {
    if (repository.softDelete(recurringTemplateId) == 0) {
      throw new IllegalArgumentException(
          "No live recurring template with id " + recurringTemplateId);
    }
    bookingService.removePending(recurringTemplateId, answer);
    LOG.info("Recurring template deleted: id={}", recurringTemplateId);
  }

  /**
   * The live templates whose end the main page reminds of today (recurring sub-plan slice g), by
   * name: those with the reminder ticked, from {@code end_date − days} on, until dismissed.
   */
  List<RecurringTemplate> endReminders() {
    LocalDate today = LocalDate.now(clock);
    return repository.findLive().stream().filter(t -> t.remindsOfEndOn(today)).toList();
  }

  /**
   * Switch a template's end reminder off: the main page's Dismiss (recurring sub-plan slice g). A
   * template already gone has nothing to remind of, so that is no error.
   */
  @Transactional
  void dismissEndReminder(long recurringTemplateId) {
    repository.dismissEndReminder(recurringTemplateId);
  }

  /**
   * The entry as the dock would commit it on the start date, in the currency the template stores.
   * Cross-currency totals the operator left blank are proposed from the rate first, as each
   * occurrence's booking will propose them.
   */
  private SplitEntry entryOf(SplitForm split, List<SplitLineDraft> lines, String spending) {
    SplitForm proposed = splitCurrencyService.withProposedTotals(split);
    return new SplitEntry(
        null,
        proposed.date(),
        proposed.accountId(),
        proposed.fundingPersonName(),
        proposed.fundingPersonDirection(),
        proposed.fundingPersonRevive(),
        null,
        null,
        blankToNull(proposed.payeeText()),
        proposed.note(),
        spending,
        proposed.fundingTotal(),
        proposed.baseTotal(),
        proposed.tagId(),
        lines,
        "confirmed");
  }

  /**
   * The currency the lines are in, as stored. A person-funded entry has no account to take it from,
   * so the dock resolves it at commit (the picked currency, else the person's one debt currency,
   * else base); a template fixes that answer now, so a booking months later books the same currency
   * the operator saw validated.
   */
  private String spendingCurrency(SplitForm split) {
    String picked = blankToNull(split.spendingCurrencyCode());
    if (picked != null || !split.hasFundingPerson()) {
      return picked;
    }
    return transactionCurrencyResolver.forFundingPerson(split.fundingPersonName(), null);
  }

  /**
   * Fill the draft's entry from the validated form: the payee and every named person are resolved
   * (created, or revived as the operator chose) so the template can store their ids.
   */
  private RecurringTemplateDraft withEntry(
      RecurringTemplateDraft schedule,
      SplitForm split,
      List<SplitLineDraft> lines,
      String spending) {
    Long personId =
        split.hasFundingPerson()
            ? personId(split.fundingPersonName(), split.fundingPersonRevive())
            : null;
    List<RecurringTemplateLineDraft> storedLines = new ArrayList<>();
    for (SplitLineDraft line : lines) {
      boolean personLine = blankToNull(line.personName()) != null;
      storedLines.add(
          new RecurringTemplateLineDraft(
              personLine ? null : line.categoryId(),
              personLine ? null : blankToNull(line.transferDirection()),
              personLine ? personId(line.personName(), line.personRevive()) : null,
              personLine ? line.personDirection() : null,
              SplitLineAmounts.parseSignedAmount(line.amount()),
              line.note(),
              line.tagIds()));
    }
    return new RecurringTemplateDraft(
        schedule.name(),
        schedule.startDate(),
        schedule.cadenceUnit(),
        schedule.cadenceN(),
        schedule.endDate(),
        schedule.leadDays(),
        schedule.confirmation(),
        schedule.endReminder(),
        schedule.endReminderDays(),
        schedule.managementUrl(),
        personId == null ? split.accountId() : null,
        personId,
        personId == null ? null : split.fundingPersonDirection(),
        payeeService.resolvePayee(null, split.payeeText()),
        blankToNull(split.note()),
        spending,
        split.tagId(),
        storedLines);
  }

  private long personId(String name, String revive) {
    return personProvisioningService
        .ensurePerson(name, "true".equalsIgnoreCase(blankToNull(revive)))
        .personId();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
