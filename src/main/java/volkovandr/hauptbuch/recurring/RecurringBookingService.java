package volkovandr.hauptbuch.recurring;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.operations.DockSplitService;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * The booking run over one template (data-model §14.3, ADR 0002). A run on day T books every
 * occurrence in {@code (booked_through, min(T + lead_days, end_date)]} through the dock's split
 * commit, stamps each booked transaction with the template and the occurrence date, and then moves
 * {@code booked_through} to the end of that window. The bookings and the cursor commit together
 * under the template's row lock, so a run can never double-book: a missed month is just a wider
 * window, and a second run the same day finds an empty one.
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
    List<LocalDate> due = template.schedule().occurrencesBetween(template.bookedThrough(), through);
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
}
