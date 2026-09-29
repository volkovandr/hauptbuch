package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tier (CLAUDE.md §6): a template's booking failure is recorded on the template, never thrown
 * (data-model §14.3, recurring sub-plan slice f), so it neither stops a run's other templates nor
 * fails the save of that template.
 */
@ExtendWith(MockitoExtension.class)
class RecurringBookingRunnerTest {

  @Mock private RecurringBookingService bookingService;

  @Test
  void bookedRunReportsHowManyAndRecordsNothing() {
    when(bookingService.run(5L)).thenReturn(2);

    OptionalInt booked = new RecurringBookingRunner(bookingService).book(5L);

    assertThat(booked).hasValue(2);
    verify(bookingService, never()).recordFailure(anyLong(), anyString());
  }

  @Test
  void failedRunRecordsItsReasonOnTheTemplate() {
    when(bookingService.run(5L))
        .thenThrow(new IllegalStateException("Account 'BankAaa-EUR' is closed"));

    OptionalInt booked = new RecurringBookingRunner(bookingService).book(5L);

    assertThat(booked).isEmpty();
    verify(bookingService).recordFailure(5L, "Account 'BankAaa-EUR' is closed");
  }

  @Test
  void failureWithoutMessageIsNamedByItsType() {
    when(bookingService.run(5L)).thenThrow(new NullPointerException());

    new RecurringBookingRunner(bookingService).book(5L);

    verify(bookingService).recordFailure(5L, "NullPointerException");
  }
}
