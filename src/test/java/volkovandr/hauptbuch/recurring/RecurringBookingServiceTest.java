package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.operations.DockSplitService;
import volkovandr.hauptbuch.operations.SplitEntry;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Unit tier (CLAUDE.md §6): the booking run over one template (data-model §14.3, recurring sub-plan
 * slice c). A run on day T books every occurrence in {@code (booked_through, min(T + lead, end)]}
 * through the dock's split commit, stamps each, and advances the cursor. "Today" comes from a fixed
 * {@link Clock}; the entry each occurrence books is {@link RecurringOccurrenceEntries}'s concern.
 */
@ExtendWith(MockitoExtension.class)
class RecurringBookingServiceTest {

  private static final long TEMPLATE_ID = 5L;
  private static final LocalDate JAN_31 = LocalDate.of(2026, 1, 31);

  @Mock private RecurringTemplateRepository repository;
  @Mock private RecurringOccurrenceEntries entries;
  @Mock private DockSplitService dockSplitService;
  @Mock private LedgerService ledgerService;

  private RecurringBookingService serviceOn(LocalDate today) {
    Clock clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    return new RecurringBookingService(repository, entries, dockSplitService, ledgerService, clock);
  }

  private static RecurringTemplate template(
      LocalDate start, String unit, int every, LocalDate end, int leadDays, LocalDate cursor) {
    return new RecurringTemplate(
        TEMPLATE_ID,
        "Streaming",
        start,
        unit,
        every,
        end,
        leadDays,
        "auto",
        cursor,
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

  private static SplitEntry entryOn(LocalDate date) {
    return new SplitEntry(
        null,
        date,
        7L,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        "confirmed");
  }

  private void locks(RecurringTemplate template) {
    when(repository.lockLive(TEMPLATE_ID)).thenReturn(Optional.of(template));
  }

  /** Book each occurrence as transaction 100, 101, … and collect the dates booked, in order. */
  private List<LocalDate> booksEachOccurrence() {
    List<LocalDate> booked = new ArrayList<>();
    when(entries.entryFor(any(), any())).thenAnswer(call -> entryOn(call.getArgument(1)));
    when(dockSplitService.commit(any()))
        .thenAnswer(
            call -> {
              booked.add(call.<SplitEntry>getArgument(0).date());
              return 99L + booked.size();
            });
    return booked;
  }

  // ── the window ──────────────────────────────────────────────────────────────

  @Test
  void runBooksEveryOccurrenceInTheWindowStampsEachAndAdvancesTheCursor() {
    LocalDate today = LocalDate.of(2026, 3, 31);
    locks(template(JAN_31, "month", 1, null, 0, JAN_31.minusDays(1)));
    List<LocalDate> booked = booksEachOccurrence();

    int count = serviceOn(today).run(TEMPLATE_ID);

    assertThat(count).isEqualTo(3);
    assertThat(booked).containsExactly(JAN_31, LocalDate.of(2026, 2, 28), today);
    verify(ledgerService).stampOccurrence(100L, TEMPLATE_ID, JAN_31);
    verify(ledgerService).stampOccurrence(101L, TEMPLATE_ID, LocalDate.of(2026, 2, 28));
    verify(ledgerService).stampOccurrence(102L, TEMPLATE_ID, today);
    verify(repository).advanceBookedThrough(TEMPLATE_ID, today);
  }

  @Test
  void eachOccurrenceIsBookedThenStampedBeforeTheNext() {
    LocalDate today = LocalDate.of(2026, 2, 28);
    locks(template(JAN_31, "month", 1, null, 0, JAN_31.minusDays(1)));
    booksEachOccurrence();

    serviceOn(today).run(TEMPLATE_ID);

    InOrder order = inOrder(repository, dockSplitService, ledgerService);
    order.verify(repository).lockLive(TEMPLATE_ID);
    order.verify(dockSplitService).commit(any());
    order.verify(ledgerService).stampOccurrence(100L, TEMPLATE_ID, JAN_31);
    order.verify(dockSplitService).commit(any());
    order.verify(ledgerService).stampOccurrence(101L, TEMPLATE_ID, today);
    order.verify(repository).advanceBookedThrough(TEMPLATE_ID, today);
  }

  @Test
  void leadTimeBooksAheadAndMovesTheCursorToTodayPlusLead() {
    LocalDate today = LocalDate.of(2026, 9, 28);
    LocalDate first = LocalDate.of(2026, 10, 1);
    locks(template(first, "month", 1, null, 5, today.minusDays(1)));
    List<LocalDate> booked = booksEachOccurrence();

    serviceOn(today).run(TEMPLATE_ID);

    assertThat(booked).containsExactly(first);
    verify(repository).advanceBookedThrough(TEMPLATE_ID, today.plusDays(5));
  }

  @Test
  void windowWithNoOccurrenceStillAdvancesTheCursor() {
    LocalDate today = LocalDate.of(2026, 2, 10);
    locks(template(JAN_31, "month", 1, null, 0, LocalDate.of(2026, 2, 5)));

    int count = serviceOn(today).run(TEMPLATE_ID);

    assertThat(count).isZero();
    verifyNoInteractions(dockSplitService, ledgerService);
    verify(repository).advanceBookedThrough(TEMPLATE_ID, today);
  }

  @Test
  void catchUpAfterDowntimeBooksEveryMissedOccurrenceOnce() {
    // Weekly on Mondays; the machine was off for 40 days. One run books all of them, and the run
    // the day after books nothing twice.
    LocalDate monday = LocalDate.of(2026, 3, 2);
    LocalDate cursor = monday.minusDays(1);
    LocalDate afterDowntime = cursor.plusDays(40);
    locks(template(monday, "week", 1, null, 0, cursor));
    List<LocalDate> booked = booksEachOccurrence();

    serviceOn(afterDowntime).run(TEMPLATE_ID);

    assertThat(booked)
        .containsExactly(
            monday,
            monday.plusWeeks(1),
            monday.plusWeeks(2),
            monday.plusWeeks(3),
            monday.plusWeeks(4),
            monday.plusWeeks(5));
    verify(repository).advanceBookedThrough(TEMPLATE_ID, afterDowntime);

    locks(template(monday, "week", 1, null, 0, afterDowntime));
    serviceOn(afterDowntime.plusDays(1)).run(TEMPLATE_ID);

    assertThat(booked).hasSize(6);
  }

  @Test
  void secondRunOnTheSameDayIsNoOp() {
    LocalDate today = LocalDate.of(2026, 3, 31);
    locks(template(JAN_31, "month", 1, null, 2, today.plusDays(2)));

    int count = serviceOn(today).run(TEMPLATE_ID);

    assertThat(count).isZero();
    verifyNoInteractions(entries, dockSplitService, ledgerService);
    verify(repository, never()).advanceBookedThrough(anyLong(), any());
  }

  // ── the end date ────────────────────────────────────────────────────────────

  @Test
  void endDateCapsTheWindowAndTheCursor() {
    LocalDate end = LocalDate.of(2026, 2, 28);
    locks(template(JAN_31, "month", 1, end, 0, JAN_31.minusDays(1)));
    List<LocalDate> booked = booksEachOccurrence();

    serviceOn(LocalDate.of(2026, 4, 15)).run(TEMPLATE_ID);

    assertThat(booked).containsExactly(JAN_31, end);
    verify(repository).advanceBookedThrough(TEMPLATE_ID, end);
  }

  @Test
  void endedTemplateWhoseCursorReachedTheEndBooksNothing() {
    LocalDate end = LocalDate.of(2026, 2, 28);
    locks(template(JAN_31, "month", 1, end, 0, end));

    serviceOn(LocalDate.of(2026, 6, 1)).run(TEMPLATE_ID);

    verifyNoInteractions(entries, dockSplitService, ledgerService);
    verify(repository, never()).advanceBookedThrough(anyLong(), any());
  }

  @Test
  void unknownOrDeletedTemplateBooksNothing() {
    when(repository.lockLive(TEMPLATE_ID)).thenReturn(Optional.empty());

    int count = serviceOn(JAN_31).run(TEMPLATE_ID);

    assertThat(count).isZero();
    verifyNoInteractions(entries, dockSplitService, ledgerService);
    verify(repository, never()).advanceBookedThrough(eq(TEMPLATE_ID), any());
  }
}
