package volkovandr.hauptbuch.recurring;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
 * skips. That only matters after {@link #rebook} has pulled the cursor back over them.
 */
@Service
class RecurringBookingService {

  private static final Logger LOG = LoggerFactory.getLogger(RecurringBookingService.class);

  private final RecurringTemplateRepository repository;
  private final RecurringOccurrenceEntries entries;
  private final DockSplitService dockSplitService;
  private final LedgerService ledgerService;
  private final Clock clock;

  RecurringBookingService(
      RecurringTemplateRepository repository,
      RecurringOccurrenceEntries entries,
      DockSplitService dockSplitService,
      LedgerService ledgerService,
      Clock clock) {
    this.repository = repository;
    this.entries = entries;
    this.dockSplitService = dockSplitService;
    this.ledgerService = ledgerService;
    this.clock = clock;
  }

  /**
   * Book a live template's due occurrences and advance its cursor. An unknown or deleted template
   * books nothing.
   *
   * @return how many occurrences were booked
   */
  @Transactional
  int run(long recurringTemplateId) {
    Optional<RecurringTemplate> locked = repository.lockLive(recurringTemplateId);
    if (locked.isEmpty()) {
      return 0;
    }
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
            .filter(occurrence -> !booked.contains(occurrence))
            .toList();
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
   * Rebook a live template after a save (data-model §14.3, ADR 0002): remove its pending rows dated
   * today or later, pull its cursor back to no later than yesterday, and run. Past rows are never
   * touched, and a start moved into the past books nothing. Pending rows dated after the template's
   * end date are kept or removed as the operator answered instead.
   *
   * @param beyondEnd the operator's answer for pending rows dated after the end date
   * @return how many occurrences were booked
   */
  @Transactional
  int rebook(long recurringTemplateId, PendingRows beyondEnd) {
    Optional<RecurringTemplate> locked = repository.lockLive(recurringTemplateId);
    if (locked.isEmpty()) {
      return 0;
    }
    LocalDate end = locked.get().endDate();
    removeAsAnswered(
        recurringTemplateId,
        date -> end != null && date.isAfter(end) ? beyondEnd : PendingRows.KEEP_PAST);
    repository.rewindBookedThrough(recurringTemplateId, LocalDate.now(clock).minusDays(1));
    return run(recurringTemplateId);
  }

  /**
   * Remove a deleted template's pending rows as the operator answered (data-model §14.3). Confirmed
   * transactions always stay.
   */
  @Transactional
  void removePending(long recurringTemplateId, PendingRows answer) {
    removeAsAnswered(recurringTemplateId, date -> answer);
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
