package volkovandr.hauptbuch.recurring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * The booking run's unattended triggers (data-model §14.3): application startup and every midnight.
 * Each live template runs in its own transaction ({@link RecurringBookingService#run}), so a
 * template that cannot book rolls back alone and keeps its cursor for the next run. Recording the
 * failure on the template for the page to show is recurring sub-plan slice f.
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
    bookDueOccurrences();
  }

  /** Book what falls due today. */
  @Scheduled(cron = "0 0 0 * * *")
  void bookAtMidnight() {
    bookDueOccurrences();
  }

  // PMD.AvoidCatchingGenericException: deliberate. This is the outermost frame of an unattended
  // job: whatever one template throws (a closed account, a missing rate, the engine's balance
  // check) must neither stop the other templates nor escape into startup or the shared scheduler
  // thread.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  void bookDueOccurrences() {
    for (RecurringTemplate template : repository.findLive()) {
      try {
        bookingService.run(template.recurringTemplateId());
      } catch (RuntimeException e) {
        LOG.warn(
            "Recurring template could not book: id={}, reason={}",
            template.recurringTemplateId(),
            e.getMessage());
      }
    }
  }
}
