package volkovandr.hauptbuch.recurring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * The booking run's unattended triggers (data-model §14.3): application startup and the {@code
 * hauptbuch.recurring.booking-cron} schedule (midnight by default). Each live template runs in its
 * own transaction ({@link RecurringBookingService#run}), so a template that cannot book rolls back
 * alone and keeps its cursor for the next run. Recording the failure on the template for the page
 * to show is recurring sub-plan slice f.
 */
@Component
class RecurringBookingScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(RecurringBookingScheduler.class);

  private final RecurringTemplateRepository repository;
  private final RecurringBookingService bookingService;

  RecurringBookingScheduler(
      RecurringTemplateRepository repository, RecurringBookingService bookingService) {
    this.repository = repository;
    this.bookingService = bookingService;
  }

  /** Book what fell due while the app was down. */
  @EventListener(ApplicationReadyEvent.class)
  void bookOnStartup() {
    bookDueOccurrences("startup");
  }

  /** Book what falls due today. */
  @Scheduled(cron = "${hauptbuch.recurring.booking-cron}")
  void bookOnSchedule() {
    bookDueOccurrences("scheduled");
  }

  // PMD.AvoidCatchingGenericException: deliberate. This is the outermost frame of an unattended
  // job: whatever one template throws (a closed account, a missing rate, the engine's balance
  // check) must neither stop the other templates nor escape into startup or the shared scheduler
  // thread.
  //
  // A run is a batch, so its finish is logged at INFO (CLAUDE.md §5) whether or not it booked
  // anything; each template that could not book has its own WARN line above it.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  int bookDueOccurrences(String trigger) {
    int booked = 0;
    int failed = 0;
    for (RecurringTemplate template : repository.findLive()) {
      try {
        booked += bookingService.run(template.recurringTemplateId());
      } catch (RuntimeException e) {
        failed++;
        LOG.warn(
            "Recurring template could not book: id={}, reason={}",
            template.recurringTemplateId(),
            e.getMessage());
      }
    }
    LOG.info(
        "Recurring booking run ({}) finished: booked {} transactions, {} templates failed",
        trigger,
        booked,
        failed);
    return booked;
  }
}
