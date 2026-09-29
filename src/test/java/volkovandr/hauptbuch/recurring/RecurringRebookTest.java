package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PendingOccurrence;
import volkovandr.hauptbuch.operations.DockSplitService;
import volkovandr.hauptbuch.operations.SplitEntry;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Unit tier (CLAUDE.md §6): editing a template once rows exist (data-model §14.3, ADR 0002,
 * recurring sub-plan slice e). Each test edits a live template over several days and checks the
 * rows it leaves, so the ledger and the template row are an in-memory fake behind the mocks: a
 * booking adds a row carrying the template's amount at that moment, a stamp dates its occurrence,
 * and the hard delete removes it. "Today" comes from a fixed {@link Clock} per step.
 */
@ExtendWith(MockitoExtension.class)
class RecurringRebookTest {

  private static final long TEMPLATE_ID = 5L;
  private static final String PENDING = "pending_review";
  private static final String CONFIRMED = "confirmed";
  private static final String OLD_AMOUNT = "9.99";
  private static final String NEW_AMOUNT = "12.99";
  private static final LocalDate MAR_1 = LocalDate.of(2026, 3, 1);
  private static final LocalDate MAR_15 = LocalDate.of(2026, 3, 15);
  private static final LocalDate MAR_16 = LocalDate.of(2026, 3, 16);
  private static final LocalDate APR_15 = LocalDate.of(2026, 4, 15);
  private static final LocalDate APR_16 = LocalDate.of(2026, 4, 16);

  @Mock private RecurringTemplateRepository repository;
  @Mock private RecurringOccurrenceEntries entries;
  @Mock private DockSplitService dockSplitService;
  @Mock private LedgerService ledgerService;

  private final Map<Long, Row> rows = new LinkedHashMap<>();
  private RecurringTemplate current;
  private long nextId = 100L;

  /** One booked transaction in the fake ledger. */
  private static final class Row {
    private final LocalDate date;
    private final String amount;
    private String lifecycle;
    private LocalDate occurrence;
    private boolean voided;

    Row(LocalDate date, String amount, String lifecycle) {
      this.date = date;
      this.amount = amount;
      this.lifecycle = lifecycle;
    }
  }

  /** A monthly {@code review} template on the 15th, booked 20 days ahead, cursor at Feb 28. */
  @BeforeEach
  void setUp() {
    current = template(MAR_15, null, 20, OLD_AMOUNT, LocalDate.of(2026, 2, 28));
    lenient().when(repository.lockLive(TEMPLATE_ID)).thenAnswer(call -> Optional.of(current));
    lenient()
        .when(repository.advanceBookedThrough(eq(TEMPLATE_ID), any()))
        .thenAnswer(call -> moveCursor(call.getArgument(1)));
    lenient()
        .when(repository.rewindBookedThrough(eq(TEMPLATE_ID), any()))
        .thenAnswer(
            call -> {
              LocalDate latest = call.getArgument(1);
              return moveCursor(
                  current.bookedThrough().isAfter(latest) ? latest : current.bookedThrough());
            });
    lenient()
        .when(entries.entryFor(any(), any()))
        .thenAnswer(call -> entryOn(call.getArgument(0), call.getArgument(1)));
    lenient().when(dockSplitService.commit(any())).thenAnswer(call -> book(call.getArgument(0)));
    lenient()
        .doAnswer(
            call -> {
              rows.get(call.<Long>getArgument(0)).occurrence = call.getArgument(2);
              return null;
            })
        .when(ledgerService)
        .stampOccurrence(anyLong(), eq(TEMPLATE_ID), any());
    lenient()
        .when(ledgerService.pendingOccurrences(TEMPLATE_ID))
        .thenAnswer(
            call ->
                rows.entrySet().stream()
                    .filter(e -> PENDING.equals(e.getValue().lifecycle) && !e.getValue().voided)
                    .map(e -> new PendingOccurrence(e.getKey(), e.getValue().date))
                    .toList());
    lenient()
        .when(ledgerService.bookedOccurrenceDates(TEMPLATE_ID))
        .thenAnswer(
            call -> rows.values().stream().map(r -> r.occurrence).collect(Collectors.toSet()));
    lenient()
        .doAnswer(
            call -> {
              Row removed = rows.remove(call.<Long>getArgument(0));
              assertThat(removed.lifecycle).isEqualTo(PENDING);
              assertThat(removed.voided).isFalse();
              return null;
            })
        .when(ledgerService)
        .deletePendingOccurrence(anyLong());
  }

  // ── the fake ──────────────────────────────────────────────────────────────────

  private static RecurringTemplate template(
      LocalDate start, LocalDate end, int leadDays, String amount, LocalDate cursor) {
    return new RecurringTemplate(
        TEMPLATE_ID,
        "Streaming",
        start,
        "month",
        1,
        end,
        leadDays,
        "review",
        cursor,
        false,
        null,
        null,
        7L,
        null,
        null,
        null,
        amount,
        null,
        null,
        null,
        null);
  }

  /** The template as saved with new settings; its cursor is the stored one. */
  private void save(LocalDate start, LocalDate end, int leadDays, String amount) {
    current = template(start, end, leadDays, amount, current.bookedThrough());
  }

  private int moveCursor(LocalDate cursor) {
    RecurringTemplate t = current;
    current = template(t.startDate(), t.endDate(), t.leadDays(), t.note(), cursor);
    return 1;
  }

  /** The entry a booking commits: the template's amount rides in the note. */
  private static SplitEntry entryOn(RecurringTemplate t, LocalDate date) {
    return new SplitEntry(
        null, date, 7L, null, null, null, null, null, null, t.note(), null, null, null, List.of(),
        List.of(), PENDING);
  }

  private long book(SplitEntry entry) {
    nextId++;
    rows.put(nextId, new Row(entry.date(), entry.note(), entry.lifecycle()));
    return nextId;
  }

  private RecurringBookingService on(LocalDate today) {
    Clock clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    return new RecurringBookingService(repository, entries, dockSplitService, ledgerService, clock);
  }

  private Row rowOn(LocalDate occurrence) {
    return rows.values().stream()
        .filter(r -> occurrence.equals(r.occurrence))
        .findFirst()
        .orElseThrow();
  }

  /** The live pending rows' occurrence dates, in date order. */
  private List<LocalDate> pendingDates() {
    return rows.values().stream()
        .filter(r -> PENDING.equals(r.lifecycle) && !r.voided)
        .map(r -> r.occurrence)
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  // ── the scenarios ─────────────────────────────────────────────────────────────

  @Test
  void amountEditReplacesTheFuturePendingRows() {
    on(MAR_1).run(TEMPLATE_ID);
    assertThat(rowOn(MAR_15).amount).isEqualTo(OLD_AMOUNT);

    save(MAR_15, null, 20, NEW_AMOUNT);
    on(MAR_1).rebook(TEMPLATE_ID, PendingRows.KEEP_PAST);

    assertThat(pendingDates()).containsExactly(MAR_15);
    assertThat(rowOn(MAR_15).amount).isEqualTo(NEW_AMOUNT);
  }

  @Test
  void movingTheDayAndBackLeavesOnePendingRowPerMonth() {
    on(MAR_1).run(TEMPLATE_ID);

    save(MAR_16, null, 20, OLD_AMOUNT);
    on(MAR_1).rebook(TEMPLATE_ID, PendingRows.KEEP_PAST);
    assertThat(pendingDates()).containsExactly(MAR_16);

    save(MAR_15, null, 20, OLD_AMOUNT);
    on(MAR_1).rebook(TEMPLATE_ID, PendingRows.KEEP_PAST);
    assertThat(pendingDates()).containsExactly(MAR_15);
    assertThat(rows).hasSize(1);
  }

  @Test
  void voidedOccurrenceStaysSkippedAcrossAnEdit() {
    on(MAR_1).run(TEMPLATE_ID);
    rowOn(MAR_15).voided = true;

    save(MAR_15, null, 20, NEW_AMOUNT);
    on(MAR_1).rebook(TEMPLATE_ID, PendingRows.KEEP_PAST);

    assertThat(pendingDates()).isEmpty();
    assertThat(rows).hasSize(1);
  }

  @Test
  void confirmedFutureRowBlocksItsOwnDateOnly() {
    on(MAR_1).run(TEMPLATE_ID);
    rowOn(MAR_15).lifecycle = CONFIRMED;

    // Lead 45 reaches April: March is the operator's fact, April books anew.
    save(MAR_15, null, 45, NEW_AMOUNT);
    on(MAR_1).rebook(TEMPLATE_ID, PendingRows.KEEP_PAST);

    assertThat(rowOn(MAR_15).amount).isEqualTo(OLD_AMOUNT);
    assertThat(pendingDates()).containsExactly(APR_15);
    assertThat(rowOn(APR_15).amount).isEqualTo(NEW_AMOUNT);
  }

  @Test
  void confirmedRowUnderTheOldScheduleCoexistsWithTheRebookedOne() {
    on(MAR_1).run(TEMPLATE_ID);
    rowOn(MAR_15).lifecycle = CONFIRMED;

    save(MAR_16, null, 20, OLD_AMOUNT);
    on(MAR_1).rebook(TEMPLATE_ID, PendingRows.KEEP_PAST);

    assertThat(rowOn(MAR_15).lifecycle).isEqualTo(CONFIRMED);
    assertThat(pendingDates()).containsExactly(MAR_16);
  }

  @Test
  void movingTheStartIntoThePastBooksNothingPast() {
    on(MAR_1).run(TEMPLATE_ID);

    save(LocalDate.of(2025, 11, 15), null, 20, OLD_AMOUNT);
    on(MAR_1).rebook(TEMPLATE_ID, PendingRows.KEEP_PAST);

    assertThat(pendingDates()).containsExactly(MAR_15);
  }

  @Test
  void raisingTheLeadTimeFillsTheGapAtOnce() {
    save(MAR_15, null, 3, OLD_AMOUNT);
    on(MAR_1).run(TEMPLATE_ID);
    assertThat(rows).isEmpty();

    save(MAR_15, null, 14, OLD_AMOUNT);
    on(MAR_1).rebook(TEMPLATE_ID, PendingRows.KEEP_PAST);

    assertThat(pendingDates()).containsExactly(MAR_15);
  }

  @Test
  void pastRowsAreNeverTouched() {
    on(MAR_1).run(TEMPLATE_ID);
    Row march = rowOn(MAR_15);

    save(MAR_15, null, 20, NEW_AMOUNT);
    on(APR_16).rebook(TEMPLATE_ID, PendingRows.KEEP_PAST);

    // The March row is still pending and past on Apr 16: kept as it was.
    assertThat(rows.values()).contains(march);
    assertThat(march.amount).isEqualTo(OLD_AMOUNT);
    assertThat(march.lifecycle).isEqualTo(PENDING);
  }

  // ── pending rows the new end date cuts off ────────────────────────────────────

  /** On Apr 20 with lead 40: March (past) and April/May (future) pending, then ended at Mar 31. */
  private void endAtMarchWith(PendingRows answer) {
    save(MAR_15, null, 40, OLD_AMOUNT);
    on(MAR_1).run(TEMPLATE_ID);
    on(LocalDate.of(2026, 4, 20)).run(TEMPLATE_ID);
    assertThat(pendingDates()).containsExactly(MAR_15, APR_15, LocalDate.of(2026, 5, 15));

    save(MAR_15, LocalDate.of(2026, 3, 31), 40, OLD_AMOUNT);
    on(LocalDate.of(2026, 4, 20)).rebook(TEMPLATE_ID, answer);
  }

  @Test
  void keepAllKeepsEveryRowTheEndCutsOff() {
    endAtMarchWith(PendingRows.KEEP_ALL);

    assertThat(pendingDates()).containsExactly(MAR_15, APR_15, LocalDate.of(2026, 5, 15));
  }

  @Test
  void keepPastRemovesTheCutOffRowsFromTodayOn() {
    endAtMarchWith(PendingRows.KEEP_PAST);

    assertThat(pendingDates()).containsExactly(MAR_15, APR_15);
  }

  @Test
  void removeAllRemovesThePastCutOffRowsToo() {
    endAtMarchWith(PendingRows.REMOVE_ALL);

    assertThat(pendingDates()).containsExactly(MAR_15);
  }

  // ── deleting the template ─────────────────────────────────────────────────────

  @Test
  void deletingKeepsOrRemovesThePendingRowsAsAnsweredAndNeverConfirmedOnes() {
    save(MAR_15, null, 40, OLD_AMOUNT);
    on(MAR_1).run(TEMPLATE_ID);
    on(LocalDate.of(2026, 4, 20)).run(TEMPLATE_ID);
    rowOn(APR_15).lifecycle = CONFIRMED;

    on(LocalDate.of(2026, 4, 20)).removePending(TEMPLATE_ID, PendingRows.KEEP_PAST);

    assertThat(pendingDates()).containsExactly(MAR_15);
    assertThat(rowOn(APR_15).lifecycle).isEqualTo(CONFIRMED);
  }
}
