package volkovandr.hauptbuch.recurring;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PendingOccurrence;
import volkovandr.hauptbuch.operations.DockSplitService;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * The booking run over one template (data-model §14.3, ADR 0002). A run on day T books every
 * occurrence in {@code (booked_through, min(T + lead_days, end_date)]} through the dock's split
 * commit, stamps each booked transaction with the template and the occurrence date, and then moves
 * {@code booked_through} to the end of that window. The bookings and the cursor commit together
 * under the template's row lock, so a run can never double-book: a missed month is just a wider
 * window, and a second run the same day finds an empty one.
 *
 * <p>A run skips any occurrence date the template already has a transaction for — confirmed,
 * pending or voided — since confirmed rows are the operator's facts and voided rows the operator's
 * skips. That only matters after {@link #rewind} has pulled the cursor back over them.
 *
 * <p>A run that cannot book throws, and everything it wrote rolls back with it, the cursor included
 * (slice f). {@link RecurringBookingRunner} records the failure on the template instead.
 */
@Service
class RecurringBookingService {

  private static final Logger LOG = LoggerFactory.getLogger(RecurringBookingService.class);

  private final RecurringTemplateRepository repository;
  private final RecurringOccurrenceEntries entries;
  private final RecurringBookability bookability;
  private final DockSplitService dockSplitService;
  private final LedgerService ledgerService;
  private final Clock clock;

  RecurringBookingService(
      RecurringTemplateRepository repository,
      RecurringOccurrenceEntries entries,
      RecurringBookability bookability,
      DockSplitService dockSplitService,
      LedgerService ledgerService,
      Clock clock) {
    this.repository = repository;
    this.entries = entries;
    this.bookability = bookability;
    this.dockSplitService = dockSplitService;
    this.ledgerService = ledgerService;
    this.clock = clock;
  }

  /**
   * Book a live template's due occurrences, advance its cursor, and clear any failure recorded on
   * it. An unknown or deleted template books nothing.
   *
   * <p>It runs behind a savepoint (a transaction of its own when the caller has none), so a
   * template save keeps the template when its booking fails: only the run's own writes roll back.
   *
   * @return how many occurrences were booked
   * @throws RuntimeException whatever made an occurrence unbookable: a reference {@link
   *     RecurringBookability} refuses, or a dock or engine refusal
   */
  @Transactional(propagation = Propagation.NESTED)
  int run(long recurringTemplateId) {
    Optional<RecurringTemplate> locked = repository.lockLive(recurringTemplateId);
    if (locked.isEmpty()) {
      return 0;
    }
    // Cleared up front: a run that fails rolls the clearing back with everything else.
    repository.clearBookingFailure(recurringTemplateId);
    RecurringTemplate template = locked.get();
    LocalDate through = LocalDate.now(clock).plusDays(template.leadDays());
    if (template.endDate() != null && template.endDate().isBefore(through)) {
      through = template.endDate();
    }
    if (!through.isAfter(template.bookedThrough())) {
      return 0;
    }
    Set<LocalDate> booked = ledgerService.bookedOccurrenceDates(recurringTemplateId);
    List<LocalDate> due =
        template.schedule().occurrencesBetween(template.bookedThrough(), through).stream()
            .filter(occurrence -> !isBooked(template, booked, occurrence))
            .toList();
    if (!due.isEmpty()) {
      bookability.requireBookable(template);
    }
    for (LocalDate occurrence : due) {
      long transactionId = dockSplitService.commit(entries.entryFor(template, occurrence));
      ledgerService.stampOccurrence(transactionId, recurringTemplateId, occurrence);
      LOG.debug(
          "Recurring occurrence booked: template={}, date={}, transaction={}",
          recurringTemplateId,
          occurrence,
          transactionId);
    }
    repository.advanceBookedThrough(recurringTemplateId, through);
    return due.size();
  }

  /**
   * Whether the occurrence is already booked. A month-end monthly start used to repeat on the
   * start's day-of-month (30 Sep → 30 Oct); a month booked that way is not booked again on its last
   * day (31 Oct), so the old date counts as the occurrence's booking too.
   */
  private static boolean isBooked(
      RecurringTemplate template, Set<LocalDate> booked, LocalDate occurrence) {
    if (booked.contains(occurrence)) {
      return true;
    }
    LocalDate start = template.startDate();
    if (!"month".equals(template.cadenceUnit()) || start.getDayOfMonth() != start.lengthOfMonth()) {
      return false;
    }
    int oldDay = Math.min(start.getDayOfMonth(), occurrence.lengthOfMonth());
    return booked.contains(occurrence.withDayOfMonth(oldDay));
  }

  /**
   * Prepare a live template's rebooking after a save (data-model §14.3, ADR 0002): remove its
   * pending rows dated today or later and pull its cursor back to no later than yesterday, for the
   * {@link #run} that follows. Past rows are never touched, and a start moved into the past books
   * nothing. Pending rows dated after the template's end date are kept or removed as the operator
   * answered instead.
   *
   * @param beyondEnd the operator's answer for pending rows dated after the end date
   */
  @Transactional
  void rewind(long recurringTemplateId, PendingRows beyondEnd) {
    Optional<RecurringTemplate> locked = repository.lockLive(recurringTemplateId);
    if (locked.isEmpty()) {
      return;
    }
    LocalDate end = locked.get().endDate();
    removeAsAnswered(
        recurringTemplateId,
        date -> end != null && date.isAfter(end) ? beyondEnd : PendingRows.KEEP_PAST);
    repository.rewindBookedThrough(recurringTemplateId, LocalDate.now(clock).minusDays(1));
  }

  /**
   * Record on a live template why its run could not book (data-model §14.3), for the main page and
   * the recurring page to name it until a run completes.
   */
  @Transactional
  void recordFailure(long recurringTemplateId, String reason) {
    repository.recordBookingFailure(recurringTemplateId, reason);
  }

  /**
   * Remove a deleted template's pending rows as the operator answered (data-model §14.3). Confirmed
   * transactions always stay.
   */
  @Transactional
  void removePending(long recurringTemplateId, PendingRows answer) {
    removeAsAnswered(recurringTemplateId, date -> answer);
  }

  /**
   * The live templates that cannot book (data-model §14.3), by name, keyed by template id: the main
   * page lists them, and the recurring page names each on its row.
   */
  Map<Long, RecurringBookingFailure> failures() {
    Map<Long, RecurringBookingFailure> failures = new LinkedHashMap<>();
    for (RecurringBookingFailure failure : repository.findLiveBookingFailures()) {
      failures.put(failure.recurringTemplateId(), failure);
    }
    return failures;
  }

  /** How many pending rows a template has, for its delete to ask whether to keep them. */
  int pendingRowCount(long recurringTemplateId) {
    return ledgerService.pendingOccurrences(recurringTemplateId).size();
  }

  /**
   * How many of a template's pending rows are dated after {@code newEnd} but not after {@code
   * oldEnd} (null for no end): the rows a save moving the end date earlier cuts off, which it asks
   * about. Rows already beyond the old end were kept by an earlier answer, and stay kept.
   */
  int pendingRowsCutOff(long recurringTemplateId, LocalDate newEnd, LocalDate oldEnd) {
    return (int)
        ledgerService.pendingOccurrences(recurringTemplateId).stream()
            .filter(row -> row.date().isAfter(newEnd))
            .filter(row -> oldEnd == null || !row.date().isAfter(oldEnd))
            .count();
  }

  /** Hard-delete each pending row that the answer picked for its date removes. */
  private void removeAsAnswered(long recurringTemplateId, Function<LocalDate, PendingRows> answer) {
    LocalDate today = LocalDate.now(clock);
    for (PendingOccurrence row : ledgerService.pendingOccurrences(recurringTemplateId)) {
      if (answer.apply(row.date()).removes(row.date(), today)) {
        ledgerService.deletePendingOccurrence(row.transactionId());
      }
    }
  }
}
