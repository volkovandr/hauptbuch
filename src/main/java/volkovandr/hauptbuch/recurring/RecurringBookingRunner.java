package volkovandr.hauptbuch.recurring;

import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one template's booking and turns a failure into a record on the template rather than an
 * exception (data-model §14.3, recurring sub-plan slice f). Every trigger goes through here: the
 * startup and scheduled runs, and a template save. The failed run has rolled back alone, its cursor
 * with it, so the next run retries what it owed; the recorded reason stays on the template, for the
 * main page and the recurring page to name, until a run completes and clears it.
 *
 * <p>It sits outside {@link RecurringBookingService} so the run's own transaction (or savepoint,
 * under a save) has ended, rolled back, before the failure is written.
 */
@Component
class RecurringBookingRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RecurringBookingRunner.class);

  private final RecurringBookingService bookingService;

  RecurringBookingRunner(RecurringBookingService bookingService) {
    this.bookingService = bookingService;
  }

  /**
   * Book a template's due occurrences.
   *
   * @return how many occurrences were booked, or empty when the template could not book
   */
  // PMD.AvoidCatchingGenericException: deliberate. Whatever one template's booking throws (a
  // closed account, a missing rate, the engine's balance check) becomes its recorded failure; it
  // must neither stop the other templates of a run nor fail the save of that template.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  OptionalInt book(long recurringTemplateId) {
    try {
      return OptionalInt.of(bookingService.run(recurringTemplateId));
    } catch (RuntimeException e) {
      String reason = reasonOf(e);
      LOG.warn("Recurring template could not book: id={}, reason={}", recurringTemplateId, reason);
      bookingService.recordFailure(recurringTemplateId, reason);
      return OptionalInt.empty();
    }
  }

  /** The exception's message, which the dock and the checks word for the operator. */
  private static String reasonOf(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }
}
