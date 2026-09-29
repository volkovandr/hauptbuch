package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonProvisioningService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.operations.DockSplitService;
import volkovandr.hauptbuch.operations.SplitCurrencyService;
import volkovandr.hauptbuch.operations.SplitEntry;
import volkovandr.hauptbuch.operations.SplitForm;
import volkovandr.hauptbuch.operations.TransactionCurrencyResolver;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Unit tier (CLAUDE.md §6): saving a recurring template (recurring sub-plan slice b). The entry is
 * dry-run through the dock's split commit before anything is stored, so the editor refuses what the
 * dock refuses; the schedule block is parsed and checked; a new template's cursor starts at
 * yesterday so nothing past is ever booked; payee and people are resolved to ids.
 */
@ExtendWith(MockitoExtension.class)
class RecurringTemplateServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);
  private static final LocalDate START = LocalDate.of(2026, 10, 31);
  private static final long BANK_ID = 7L;
  private static final long STREAMING_ID = 11L;

  @Mock private RecurringTemplateRepository repository;
  @Mock private DockSplitService dockSplitService;

  // Refused saves stop before the totals are proposed, so this stub is not used by every test.
  @Mock private SplitCurrencyService splitCurrencyService;

  @Mock private PayeeService payeeService;
  @Mock private PersonProvisioningService personProvisioningService;
  @Mock private TransactionCurrencyResolver transactionCurrencyResolver;
  @Mock private RecurringBookingService bookingService;
  @Mock private RecurringBookingRunner runner;

  private RecurringTemplateService service;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    service =
        new RecurringTemplateService(
            repository,
            dockSplitService,
            splitCurrencyService,
            payeeService,
            personProvisioningService,
            transactionCurrencyResolver,
            bookingService,
            runner,
            clock);
  }

  /** The dock's totals proposal, which a saved entry reaches only after its schedule checks out. */
  private void proposesNothing() {
    when(splitCurrencyService.withProposedTotals(any())).thenAnswer(call -> call.getArgument(0));
  }

  private static RecurringScheduleForm schedule(Long id, String name) {
    return new RecurringScheduleForm(
        id, name, "1", "month", "none", "", "", "3", "review", "", "", "");
  }

  private static SplitForm split(Long accountId, String fundingPerson, String amount) {
    return split(accountId, fundingPerson, amount, START);
  }

  private static SplitForm split(
      Long accountId, String fundingPerson, String amount, LocalDate start) {
    return new SplitForm(
        null,
        start,
        accountId,
        fundingPerson,
        fundingPerson.isEmpty() ? "" : "BY",
        "",
        "ShopAaa",
        "plan",
        amount,
        null,
        "",
        "",
        List.of("Streaming"),
        List.of(String.valueOf(STREAMING_ID)),
        List.of("expense"),
        List.of(""),
        List.of(""),
        List.of(""),
        List.of(""),
        List.of(amount),
        List.of("line note"),
        List.of(5L),
        List.of(List.of(5L, 6L)),
        null,
        null,
        null,
        null,
        null,
        false);
  }

  private RecurringTemplateDraft insertedDraft(LocalDate bookedThrough) {
    ArgumentCaptor<RecurringTemplateDraft> draft =
        ArgumentCaptor.forClass(RecurringTemplateDraft.class);
    verify(repository).insert(draft.capture(), eq(bookedThrough));
    return draft.getValue();
  }

  // ── a new template ──────────────────────────────────────────────────────────

  @Test
  void newTemplateIsDryRunOnTheStartDateThenStoredWithItsCursorAtYesterday() {
    proposesNothing();
    when(payeeService.resolvePayee(null, "ShopAaa")).thenReturn(3L);
    when(repository.insert(any(), any())).thenReturn(42L);

    long id = service.save(schedule(null, " Streaming "), split(BANK_ID, "", "9,99"));

    assertThat(id).isEqualTo(42L);
    ArgumentCaptor<SplitEntry> entry = ArgumentCaptor.forClass(SplitEntry.class);
    verify(dockSplitService).validate(entry.capture());
    assertThat(entry.getValue().date()).isEqualTo(START);
    assertThat(entry.getValue().accountId()).isEqualTo(BANK_ID);
    assertThat(entry.getValue().lines()).hasSize(1);

    RecurringTemplateDraft draft = insertedDraft(TODAY.minusDays(1));
    assertThat(draft.name()).isEqualTo("Streaming");
    assertThat(draft.startDate()).isEqualTo(START);
    assertThat(draft.cadenceUnit()).isEqualTo("month");
    assertThat(draft.cadenceN()).isEqualTo(1);
    assertThat(draft.endDate()).isNull();
    assertThat(draft.leadDays()).isEqualTo(3);
    assertThat(draft.confirmation()).isEqualTo("review");
    assertThat(draft.accountId()).isEqualTo(BANK_ID);
    assertThat(draft.personId()).isNull();
    assertThat(draft.payeeId()).isEqualTo(3L);
    assertThat(draft.note()).isEqualTo("plan");
    assertThat(draft.tagIds()).containsExactly(5L);
    assertThat(draft.lines()).hasSize(1);
    RecurringTemplateLineDraft line = draft.lines().get(0);
    assertThat(line.accountId()).isEqualTo(STREAMING_ID);
    assertThat(line.amount()).isEqualByComparingTo("9.99");
    assertThat(line.note()).isEqualTo("line note");
    assertThat(line.tagIds()).containsExactly(5L, 6L);
  }

  @Test
  void stornoLineKeepsItsSign() {
    proposesNothing();
    when(repository.insert(any(), any())).thenReturn(1L);

    service.save(schedule(null, "Refund"), split(BANK_ID, "", "-4,50"));

    assertThat(insertedDraft(TODAY.minusDays(1)).lines().get(0).amount())
        .isEqualByComparingTo("-4.50");
  }

  @Test
  void fundingPersonIsResolvedToTheirId() {
    proposesNothing();
    when(personProvisioningService.ensurePerson("Doe", false))
        .thenReturn(new Person(9L, "Doe", null));
    when(repository.insert(any(), any())).thenReturn(1L);

    service.save(schedule(null, "Pocket money"), split(null, "Doe", "50"));

    RecurringTemplateDraft draft = insertedDraft(TODAY.minusDays(1));
    assertThat(draft.accountId()).isNull();
    assertThat(draft.personId()).isEqualTo(9L);
    assertThat(draft.fundingPersonDirection()).isEqualTo("BY");
  }

  @Test
  void personFundedTemplateFixesTheCurrencyTheDockWouldPick() {
    proposesNothing();
    when(personProvisioningService.ensurePerson("Doe", false))
        .thenReturn(new Person(9L, "Doe", null));
    when(transactionCurrencyResolver.forFundingPerson("Doe", null)).thenReturn("USD");
    when(repository.insert(any(), any())).thenReturn(1L);

    service.save(schedule(null, "Pocket money"), split(null, "Doe", "50"));

    assertThat(insertedDraft(TODAY.minusDays(1)).spendingCurrencyCode()).isEqualTo("USD");
    ArgumentCaptor<SplitEntry> entry = ArgumentCaptor.forClass(SplitEntry.class);
    verify(dockSplitService).validate(entry.capture());
    assertThat(entry.getValue().spendingCurrencyCode()).isEqualTo("USD");
  }

  @Test
  void peopleAreResolvedBeforeTheDryRunSoItCreatesNone() {
    proposesNothing();
    when(personProvisioningService.ensurePerson("Doe", false))
        .thenReturn(new Person(9L, "Doe", null));
    when(repository.insert(any(), any())).thenReturn(1L);

    service.save(schedule(null, "Pocket money"), split(null, "Doe", "50"));

    InOrder order = inOrder(personProvisioningService, dockSplitService);
    order.verify(personProvisioningService).ensurePerson("Doe", false);
    order.verify(dockSplitService).validate(any());
  }

  @Test
  void endAfterCountStoresTheLastOccurrencesDate() {
    proposesNothing();
    when(repository.insert(any(), any())).thenReturn(1L);
    RecurringScheduleForm afterThree =
        new RecurringScheduleForm(
            null, "Loan", "1", "month", "after", "", "3", "0", "auto", "", "", "");

    service.save(afterThree, split(BANK_ID, "", "100"));

    // 31 Oct → 30 Nov → 31 Dec
    assertThat(insertedDraft(TODAY.minusDays(1)).endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
  }

  @Test
  void blankLeadTimeAndConfirmationDefault() {
    proposesNothing();
    when(repository.insert(any(), any())).thenReturn(1L);
    RecurringScheduleForm defaults =
        new RecurringScheduleForm(null, "Gym", "1", "month", "none", "", "", "", "", "", "", "");

    service.save(defaults, split(BANK_ID, "", "30"));

    RecurringTemplateDraft draft = insertedDraft(TODAY.minusDays(1));
    assertThat(draft.leadDays()).isZero();
    assertThat(draft.confirmation()).isEqualTo("auto");
  }

  // ── refusals ────────────────────────────────────────────────────────────────

  @Test
  void refusesBlankName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.save(schedule(null, " "), split(BANK_ID, "", "9,99")))
        .withMessage("A template needs a name");
    verify(dockSplitService, never()).validate(any());
    verify(repository, never()).insert(any(), any());
  }

  @Test
  void refusesCadenceBelowOne() {
    RecurringScheduleForm zero =
        new RecurringScheduleForm(
            null, "Gym", "0", "month", "none", "", "", "0", "auto", "", "", "");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.save(zero, split(BANK_ID, "", "30")))
        .withMessage("The cadence must be a whole number of at least 1");
  }

  @Test
  void refusesEndDateBeforeTheStart() {
    RecurringScheduleForm backwards =
        new RecurringScheduleForm(
            null, "Gym", "1", "month", "date", "2026-10-01", "", "0", "auto", "", "", "");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.save(backwards, split(BANK_ID, "", "30")))
        .withMessage("The end date is before the start date");
  }

  @Test
  void refusesManagementLinkThatIsNotWebAddress() {
    RecurringScheduleForm script =
        new RecurringScheduleForm(
            null, "Gym", "1", "month", "none", "", "", "0", "auto", "javascript:alert(1)", "", "");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.save(script, split(BANK_ID, "", "30")))
        .withMessageContaining("https://");
  }

  @Test
  void refusesEntryWithNoFundingSource() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.save(schedule(null, "Gym"), split(null, "", "30")))
        .withMessage("An account or person is required");
    verify(dockSplitService, never()).validate(any());
  }

  @Test
  void whateverTheDockRefusesIsRefusedAndNothingIsStored() {
    proposesNothing();
    doThrow(new IllegalArgumentException("A transfer needs two different accounts"))
        .when(dockSplitService)
        .validate(any());

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.save(schedule(null, "Gym"), split(BANK_ID, "", "30")))
        .withMessage("A transfer needs two different accounts");
    verify(repository, never()).insert(any(), any());
  }

  // ── booking on save (slice c) ──────────────────────────────────────────────

  private static RecurringScheduleForm answering(String pastOccurrences) {
    return new RecurringScheduleForm(
        null, "Gym", "1", "month", "none", "", "", "0", "auto", "", pastOccurrences, "");
  }

  @Test
  void newTemplateStartingInThePastCountsItsPastOccurrences() {
    // Monthly from 31 Jul with today 28 Sep: 31 Jul and 31 Aug are past; 30 Sep is not.
    LocalDate start = LocalDate.of(2026, 7, 31);

    int past = service.pastOccurrences(answering(""), split(BANK_ID, "", "30", start));

    assertThat(past).isEqualTo(2);
  }

  @Test
  void todaysOccurrenceIsNotPast() {
    assertThat(service.pastOccurrences(answering(""), split(BANK_ID, "", "30", TODAY))).isZero();
  }

  @Test
  void futureStartOrExistingTemplateHasNoPastToAskAbout() {
    assertThat(service.pastOccurrences(answering(""), split(BANK_ID, "", "30"))).isZero();
    assertThat(
            service.pastOccurrences(
                schedule(42L, "Gym"), split(BANK_ID, "", "30", LocalDate.of(2026, 1, 31))))
        .isZero();
  }

  @Test
  void bookingThePastStartsTheCursorTheDayBeforeTheStart() {
    proposesNothing();
    LocalDate start = LocalDate.of(2026, 7, 31);
    when(repository.insert(any(), any())).thenReturn(42L);

    service.save(answering(RecurringScheduleForm.BOOK_PAST), split(BANK_ID, "", "30", start));

    assertThat(insertedDraft(start.minusDays(1)).startDate()).isEqualTo(start);
  }

  @Test
  void startingFromTheNextStartsTheCursorAtYesterday() {
    proposesNothing();
    when(repository.insert(any(), any())).thenReturn(42L);

    service.save(
        answering(RecurringScheduleForm.SKIP_PAST),
        split(BANK_ID, "", "30", LocalDate.of(2026, 7, 31)));

    assertThat(insertedDraft(TODAY.minusDays(1)).startDate()).isEqualTo(LocalDate.of(2026, 7, 31));
  }

  @Test
  void saveRunsTheBookingForThatTemplateOnceItIsStored() {
    proposesNothing();
    when(repository.insert(any(), any())).thenReturn(42L);

    service.save(schedule(null, "Streaming"), split(BANK_ID, "", "9,99"));

    InOrder order = inOrder(repository, runner);
    order.verify(repository).insert(any(), any());
    order.verify(runner).book(42L);
  }

  @Test
  void editRebooksThatTemplateAfterUpdatingIt() {
    proposesNothing();
    when(repository.update(eq(42L), any())).thenReturn(1);

    service.save(schedule(42L, "Streaming"), split(BANK_ID, "", "12,99"));

    // Unasked, nothing is newly cut off: rows already beyond the end were kept by an answer.
    InOrder order = inOrder(repository, bookingService, runner);
    order.verify(repository).update(eq(42L), any());
    order.verify(bookingService).rewind(42L, PendingRows.KEEP_ALL);
    order.verify(runner).book(42L);
  }

  @Test
  void editCarriesTheAnswerForRowsTheEndDateCutsOff() {
    proposesNothing();
    when(repository.update(eq(42L), any())).thenReturn(1);
    RecurringScheduleForm ended =
        new RecurringScheduleForm(
            42L,
            "Gym",
            "1",
            "month",
            "date",
            "2026-11-30",
            "",
            "0",
            "review",
            "",
            "",
            "remove-all");

    service.save(ended, split(BANK_ID, "", "30"));

    verify(bookingService).rewind(42L, PendingRows.REMOVE_ALL);
  }

  // ── pending rows an end date or a delete leaves behind ──────────────────────

  private static RecurringScheduleForm endingOn(Long id, String endDate) {
    return new RecurringScheduleForm(
        id, "Gym", "1", "month", "date", endDate, "", "0", "review", "", "", "");
  }

  @Test
  void countsThePendingRowsBetweenTheNewAndTheStoredEndDate() {
    LocalDate storedEnd = LocalDate.of(2027, 3, 31);
    when(repository.findById(42L)).thenReturn(java.util.Optional.of(storedTemplate(storedEnd)));
    when(bookingService.pendingRowsCutOff(42L, LocalDate.of(2026, 11, 30), storedEnd))
        .thenReturn(2);

    assertThat(service.cutOffPending(endingOn(42L, "2026-11-30"), split(BANK_ID, "", "30")))
        .isEqualTo(2);
  }

  private static RecurringTemplate storedTemplate(LocalDate endDate) {
    return new RecurringTemplate(
        42L, "Gym", START, "month", 1, endDate, 0, "review", TODAY, false, null, null, BANK_ID,
        null, null, null, null, null, null, null, null);
  }

  @Test
  void noEndDateOrNewTemplateCutsNothingOff() {
    assertThat(service.cutOffPending(schedule(42L, "Gym"), split(BANK_ID, "", "30"))).isZero();
    assertThat(service.cutOffPending(endingOn(null, "2026-11-30"), split(BANK_ID, "", "30")))
        .isZero();
    verify(bookingService, never()).pendingRowsCutOff(eq(42L), any(), any());
  }

  // ── edit and delete ─────────────────────────────────────────────────────────

  @Test
  void editUpdatesTheTemplateAndLeavesItsCursorAlone() {
    proposesNothing();
    when(repository.update(eq(42L), any())).thenReturn(1);

    long id = service.save(schedule(42L, "Streaming"), split(BANK_ID, "", "12,99"));

    assertThat(id).isEqualTo(42L);
    verify(repository, never()).insert(any(), any());
  }

  @Test
  void editOfMissingTemplateIsRefused() {
    proposesNothing();
    when(repository.update(eq(42L), any())).thenReturn(0);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.save(schedule(42L, "Streaming"), split(BANK_ID, "", "12,99")));
  }

  @Test
  void deleteSoftDeletesThenRemovesPendingRowsAsAnswered() {
    when(repository.softDelete(42L)).thenReturn(1);

    service.delete(42L, PendingRows.KEEP_PAST);

    InOrder order = inOrder(repository, bookingService);
    order.verify(repository).softDelete(42L);
    order.verify(bookingService).removePending(42L, PendingRows.KEEP_PAST);
  }

  @Test
  void deleteOfMissingTemplateIsRefused() {
    when(repository.softDelete(42L)).thenReturn(0);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.delete(42L, PendingRows.KEEP_ALL));
    verify(bookingService, never()).removePending(eq(42L), any());
  }
}
