package volkovandr.hauptbuch.recurring;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Unit tier (CLAUDE.md §6): the startup and midnight triggers (data-model §14.3, recurring sub-plan
 * slice c) run every live template, each in its own transaction, so one template that cannot book
 * does not stop the others.
 */
@ExtendWith(MockitoExtension.class)
class RecurringBookingSchedulerTest {

  @Mock private RecurringTemplateRepository repository;
  @Mock private RecurringBookingService bookingService;

  private static RecurringTemplate template(long id) {
    return new RecurringTemplate(
        id,
        "Template " + id,
        LocalDate.of(2026, 1, 1),
        "month",
        1,
        null,
        0,
        "auto",
        LocalDate.of(2025, 12, 31),
        false,
        null,
        null,
        7L,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void runsEveryLiveTemplateEvenWhenOneCannotBook() {
    when(repository.findLive()).thenReturn(List.of(template(1L), template(2L)));
    when(bookingService.run(1L)).thenThrow(new IllegalArgumentException("account closed"));

    new RecurringBookingScheduler(repository, bookingService).bookDueOccurrences();

    verify(bookingService).run(1L);
    verify(bookingService).run(2L);
  }
}
