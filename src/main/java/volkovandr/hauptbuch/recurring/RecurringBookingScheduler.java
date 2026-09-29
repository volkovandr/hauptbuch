package volkovandr.hauptbuch.recurring;

import java.util.OptionalInt;
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
 * own transaction through {@link RecurringBookingRunner}, so a template that cannot book rolls back
 * alone, keeps its cursor for the next run, and has its failure recorded for the pages to show.
 */
@Component
class RecurringBookingScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(RecurringBookingScheduler.class);

  private final RecurringTemplateRepository repository;
  private final RecurringBookingRunner runner;

  RecurringBookingScheduler(RecurringTemplateRepository repository, RecurringBookingRunner runner) {
    this.repository = repository;
    this.runner = runner;
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

  // A run is a batch, so its finish is logged at INFO (CLAUDE.md §5) whether or not it booked
  // anything; each template that could not book has its own WARN line above it.
  int bookDueOccurrences(String trigger) {
    int booked = 0;
    int failed = 0;
    for (RecurringTemplate template : repository.findLive()) {
      OptionalInt count = runner.book(template.recurringTemplateId());
      if (count.isPresent()) {
        booked += count.getAsInt();
      } else {
        failed++;
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
