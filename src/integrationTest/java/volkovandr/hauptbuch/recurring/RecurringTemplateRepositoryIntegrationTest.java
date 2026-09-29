package volkovandr.hauptbuch.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;

/**
 * Integration tier (CLAUDE.md §6): V31 applies, and every {@link RecurringTemplateRepository}
 * method round-trips a template's header, lines and both tag junctions against real Postgres. Also
 * pins the schema's own guards: one funding source per template, one target per line, and the
 * occurrence stamp on {@code transaction} (both-or-neither, unique per template and date). Each
 * test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RecurringTemplateRepositoryIntegrationTest {

  private static final LocalDate START = LocalDate.of(2026, 1, 31);

  @Autowired RecurringTemplateRepository repository;
  @Autowired JdbcClient jdbcClient;

  private long bankAccountId;
  private long otherAccountId;
  private long streamingId;
  private long personId;
  private long payeeId;
  private long tagOneId;
  private long tagTwoId;

  @BeforeEach
  void seed() {
    bankAccountId = account("BankAaa-EUR", "asset");
    otherAccountId = account("BankBbb-EUR", "asset");
    streamingId = account("Streaming", "expense");
    personId =
        jdbcClient
            .sql("insert into person (name) values ('Doe') returning person_id")
            .query(Long.class)
            .single();
    payeeId =
        jdbcClient
            .sql("insert into payee (name) values ('ShopAaa') returning payee_id")
            .query(Long.class)
            .single();
    tagOneId = tag("Household");
    tagTwoId = tag("Media");
  }

  private long account(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:name, :type, 'EUR') "
                + "returning account_id")
        .param("name", name)
        .param("type", type)
        .query(Long.class)
        .single();
  }

  private long tag(String name) {
    return jdbcClient
        .sql("insert into tag (name) values (:name) returning tag_id")
        .param("name", name)
        .query(Long.class)
        .single();
  }

  private RecurringTemplateDraft draft(
      String name, Long accountId, Long fundingPersonId, List<RecurringTemplateLineDraft> lines) {
    return new RecurringTemplateDraft(
        name,
        START,
        "month",
        1,
        null,
        0,
        "auto",
        false,
        null,
        null,
        accountId,
        fundingPersonId,
        fundingPersonId == null ? null : "BY",
        null,
        null,
        null,
        List.of(),
        lines);
  }

  private RecurringTemplateLineDraft categoryLine(String amount) {
    return new RecurringTemplateLineDraft(
        streamingId, null, null, null, new BigDecimal(amount), null, List.of());
  }

  // ── insert / find ───────────────────────────────────────────────────────────

  @Test
  void insertRoundTripsEveryHeaderField() {
    RecurringTemplateDraft full =
        new RecurringTemplateDraft(
            "Streaming",
            START,
            "month",
            2,
            LocalDate.of(2026, 12, 31),
            3,
            "review",
            true,
            14,
            "https://example.org/account",
            bankAccountId,
            null,
            null,
            payeeId,
            "monthly plan",
            "CHF",
            List.of(tagOneId, tagTwoId, tagOneId),
            List.of(categoryLine("9.99")));

    long id = repository.insert(full, START.minusDays(1));

    RecurringTemplate stored = repository.findById(id).orElseThrow();
    assertThat(stored.recurringTemplateId()).isEqualTo(id);
    assertThat(stored.name()).isEqualTo("Streaming");
    assertThat(stored.startDate()).isEqualTo(START);
    assertThat(stored.cadenceUnit()).isEqualTo("month");
    assertThat(stored.cadenceN()).isEqualTo(2);
    assertThat(stored.endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    assertThat(stored.leadDays()).isEqualTo(3);
    assertThat(stored.confirmation()).isEqualTo("review");
    assertThat(stored.bookedThrough()).isEqualTo(LocalDate.of(2026, 1, 30));
    assertThat(stored.endReminder()).isTrue();
    assertThat(stored.endReminderDays()).isEqualTo(14);
    assertThat(stored.managementUrl()).isEqualTo("https://example.org/account");
    assertThat(stored.accountId()).isEqualTo(bankAccountId);
    assertThat(stored.personId()).isNull();
    assertThat(stored.fundingPersonDirection()).isNull();
    assertThat(stored.payeeId()).isEqualTo(payeeId);
    assertThat(stored.note()).isEqualTo("monthly plan");
    assertThat(stored.spendingCurrencyCode()).isEqualTo("CHF");
    assertThat(stored.createdAt()).isNotNull();
    assertThat(stored.updatedAt()).isNotNull();
    assertThat(stored.deletedAt()).isNull();
    // a doubly-picked chip is stored once
    assertThat(repository.findTagIds(id)).containsExactly(tagOneId, tagTwoId);
  }

  @Test
  void insertRoundTripsCategoryTransferAndPersonLinesInOrder() {
    List<RecurringTemplateLineDraft> lines =
        List.of(
            new RecurringTemplateLineDraft(
                streamingId, null, null, null, new BigDecimal("12.50"), "plan", List.of(tagTwoId)),
            new RecurringTemplateLineDraft(
                otherAccountId, "TO", null, null, new BigDecimal("100"), null, List.of()),
            new RecurringTemplateLineDraft(
                null, null, personId, "FOR", new BigDecimal("-5"), null, List.of(tagOneId)));

    long id = repository.insert(draft("Mixed", bankAccountId, null, lines), START.minusDays(1));

    List<RecurringTemplateLine> stored = repository.findLines(id);
    assertThat(stored).hasSize(3);
    RecurringTemplateLine category = stored.get(0);
    assertThat(category.recurringTemplateId()).isEqualTo(id);
    assertThat(category.accountId()).isEqualTo(streamingId);
    assertThat(category.transferDirection()).isNull();
    assertThat(category.personId()).isNull();
    assertThat(category.amount()).isEqualByComparingTo("12.50");
    assertThat(category.note()).isEqualTo("plan");
    assertThat(category.sortOrder()).isZero();
    assertThat(repository.findLineTagIds(category.recurringTemplateLineId()))
        .containsExactly(tagTwoId);

    RecurringTemplateLine transfer = stored.get(1);
    assertThat(transfer.accountId()).isEqualTo(otherAccountId);
    assertThat(transfer.transferDirection()).isEqualTo("TO");
    assertThat(transfer.sortOrder()).isEqualTo(1);
    assertThat(repository.findLineTagIds(transfer.recurringTemplateLineId())).isEmpty();

    RecurringTemplateLine person = stored.get(2);
    assertThat(person.accountId()).isNull();
    assertThat(person.personId()).isEqualTo(personId);
    assertThat(person.personDirection()).isEqualTo("FOR");
    assertThat(person.amount()).isEqualByComparingTo("-5"); // a storno keeps its sign
    assertThat(repository.findLineTagIds(person.recurringTemplateLineId()))
        .containsExactly(tagOneId);
  }

  @Test
  void insertRoundTripsFundingPerson() {
    long id =
        repository.insert(
            draft("Pocket money", null, personId, List.of(categoryLine("50"))), START.minusDays(1));

    RecurringTemplate stored = repository.findById(id).orElseThrow();
    assertThat(stored.accountId()).isNull();
    assertThat(stored.personId()).isEqualTo(personId);
    assertThat(stored.fundingPersonDirection()).isEqualTo("BY");
  }

  @Test
  void storedTemplateAnswersItsSchedule() {
    long id =
        repository.insert(
            draft("Rent", bankAccountId, null, List.of(categoryLine("800"))), START.minusDays(1));

    Schedule schedule = repository.findById(id).orElseThrow().schedule();

    assertThat(schedule.nextOccurrences(START, 2))
        .containsExactly(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31));
  }

  @Test
  void findByIdOfUnknownTemplateIsEmpty() {
    assertThat(repository.findById(-1)).isEmpty();
  }

  // ── update ──────────────────────────────────────────────────────────────────

  @Test
  void updateReplacesHeaderTagsAndLinesButKeepsTheCursor() {
    RecurringTemplateDraft original =
        new RecurringTemplateDraft(
            "Streaming",
            START,
            "month",
            1,
            null,
            0,
            "auto",
            false,
            null,
            null,
            bankAccountId,
            null,
            null,
            null,
            null,
            null,
            List.of(tagOneId),
            List.of(
                new RecurringTemplateLineDraft(
                    streamingId, null, null, null, new BigDecimal("9.99"), null, List.of(tagOneId)),
                categoryLine("1")));
    long id = repository.insert(original, LocalDate.of(2026, 3, 31));

    RecurringTemplateDraft edited =
        new RecurringTemplateDraft(
            "Streaming premium",
            LocalDate.of(2026, 2, 15),
            "week",
            2,
            LocalDate.of(2026, 6, 30),
            5,
            "review",
            true,
            7,
            "https://example.org/plan",
            null,
            personId,
            "FOR",
            payeeId,
            "upgraded",
            "USD",
            List.of(tagTwoId),
            List.of(
                new RecurringTemplateLineDraft(
                    streamingId,
                    null,
                    null,
                    null,
                    new BigDecimal("14.99"),
                    "premium",
                    List.of(tagTwoId))));

    assertThat(repository.update(id, edited)).isEqualTo(1);

    RecurringTemplate stored = repository.findById(id).orElseThrow();
    assertThat(stored.name()).isEqualTo("Streaming premium");
    assertThat(stored.startDate()).isEqualTo(LocalDate.of(2026, 2, 15));
    assertThat(stored.cadenceUnit()).isEqualTo("week");
    assertThat(stored.cadenceN()).isEqualTo(2);
    assertThat(stored.endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    assertThat(stored.leadDays()).isEqualTo(5);
    assertThat(stored.confirmation()).isEqualTo("review");
    assertThat(stored.endReminder()).isTrue();
    assertThat(stored.endReminderDays()).isEqualTo(7);
    assertThat(stored.managementUrl()).isEqualTo("https://example.org/plan");
    assertThat(stored.accountId()).isNull();
    assertThat(stored.personId()).isEqualTo(personId);
    assertThat(stored.fundingPersonDirection()).isEqualTo("FOR");
    assertThat(stored.payeeId()).isEqualTo(payeeId);
    assertThat(stored.note()).isEqualTo("upgraded");
    assertThat(stored.spendingCurrencyCode()).isEqualTo("USD");
    assertThat(stored.bookedThrough()).isEqualTo(LocalDate.of(2026, 3, 31));
    assertThat(repository.findTagIds(id)).containsExactly(tagTwoId);

    List<RecurringTemplateLine> lines = repository.findLines(id);
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).amount()).isEqualByComparingTo("14.99");
    assertThat(lines.get(0).note()).isEqualTo("premium");
    assertThat(repository.findLineTagIds(lines.get(0).recurringTemplateLineId()))
        .containsExactly(tagTwoId);
    Long orphanLineTags =
        jdbcClient
            .sql("select count(*) from recurring_template_line_tag where tag_id = :tagId")
            .param("tagId", tagOneId)
            .query(Long.class)
            .single();
    assertThat(orphanLineTags).isZero();
  }

  @Test
  void updateOfUnknownTemplateChangesNothing() {
    assertThat(repository.update(-1, draft("Ghost", bankAccountId, null, List.of()))).isZero();
  }

  @Test
  void updateOfDeletedTemplateChangesNothing() {
    long id =
        repository.insert(
            draft("Gym", bankAccountId, null, List.of(categoryLine("30"))), START.minusDays(1));
    repository.softDelete(id);

    assertThat(repository.update(id, draft("Gym again", bankAccountId, null, List.of()))).isZero();
    assertThat(repository.findById(id).orElseThrow().name()).isEqualTo("Gym");
    assertThat(repository.findLines(id)).hasSize(1);
  }

  // ── soft delete / live list ─────────────────────────────────────────────────

  @Test
  void findLiveListsLiveTemplatesByNameAndSkipsDeletedOnes() {
    long rent =
        repository.insert(
            draft("rent", bankAccountId, null, List.of(categoryLine("800"))), START.minusDays(1));
    long gym =
        repository.insert(
            draft("Gym", bankAccountId, null, List.of(categoryLine("30"))), START.minusDays(1));
    long gone =
        repository.insert(
            draft("Anime", bankAccountId, null, List.of(categoryLine("8"))), START.minusDays(1));

    assertThat(repository.softDelete(gone)).isEqualTo(1);

    assertThat(repository.findLive())
        .extracting(RecurringTemplate::recurringTemplateId)
        .containsSubsequence(gym, rent)
        .doesNotContain(gone);
  }

  @Test
  void softDeleteKeepsTheRowAndIsIdempotent() {
    long id =
        repository.insert(
            draft("Gym", bankAccountId, null, List.of(categoryLine("30"))), START.minusDays(1));

    assertThat(repository.softDelete(id)).isEqualTo(1);
    assertThat(repository.softDelete(id)).isZero();
    assertThat(repository.findById(id).orElseThrow().deletedAt()).isNotNull();
    assertThat(repository.findLines(id)).hasSize(1);
  }

  // ── the booking run: lock and cursor ───────────────────────────────────────

  @Test
  void lockLiveReadsLiveTemplateAndSkipsDeletedOnes() {
    long live =
        repository.insert(
            draft("Gym", bankAccountId, null, List.of(categoryLine("30"))), START.minusDays(1));
    long deleted =
        repository.insert(
            draft("Club", bankAccountId, null, List.of(categoryLine("5"))), START.minusDays(1));
    repository.softDelete(deleted);

    assertThat(repository.lockLive(live).orElseThrow().name()).isEqualTo("Gym");
    assertThat(repository.lockLive(deleted)).isEmpty();
    assertThat(repository.lockLive(-1L)).isEmpty();
  }

  @Test
  void advanceBookedThroughMovesTheCursorOfLiveTemplateOnly() {
    long id =
        repository.insert(
            draft("Gym", bankAccountId, null, List.of(categoryLine("30"))), START.minusDays(1));

    assertThat(repository.advanceBookedThrough(id, START.plusDays(3))).isEqualTo(1);
    assertThat(repository.findById(id).orElseThrow().bookedThrough()).isEqualTo(START.plusDays(3));

    repository.softDelete(id);
    assertThat(repository.advanceBookedThrough(id, START.plusDays(9))).isZero();
    assertThat(repository.findById(id).orElseThrow().bookedThrough()).isEqualTo(START.plusDays(3));
  }

  @Test
  void rewindBookedThroughOnlyEverMovesTheCursorBack() {
    long id =
        repository.insert(
            draft("Gym", bankAccountId, null, List.of(categoryLine("30"))), START.plusDays(5));

    assertThat(repository.rewindBookedThrough(id, START.plusDays(9))).isEqualTo(1);
    assertThat(repository.findById(id).orElseThrow().bookedThrough()).isEqualTo(START.plusDays(5));

    assertThat(repository.rewindBookedThrough(id, START)).isEqualTo(1);
    assertThat(repository.findById(id).orElseThrow().bookedThrough()).isEqualTo(START);
  }

  // ── schema guards ───────────────────────────────────────────────────────────

  @Test
  void templateNeedsExactlyOneFundingSource() {
    assertThatThrownBy(() -> repository.insert(draft("None", null, null, List.of()), START))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void lineNeedsExactlyOneTarget() {
    RecurringTemplateLineDraft both =
        new RecurringTemplateLineDraft(
            streamingId, null, personId, "FOR", BigDecimal.TEN, null, List.of());

    assertThatThrownBy(
            () -> repository.insert(draft("Both", bankAccountId, null, List.of(both)), START))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void endDateMayNotPrecedeTheStart() {
    RecurringTemplateDraft backwards =
        new RecurringTemplateDraft(
            "Backwards",
            START,
            "month",
            1,
            START.minusDays(1),
            0,
            "auto",
            false,
            null,
            null,
            bankAccountId,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of());

    assertThatThrownBy(() -> repository.insert(backwards, START))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ── the occurrence stamp on transaction ─────────────────────────────────────

  private long stampedTransaction(Long templateId, LocalDate occurrenceDate) {
    return jdbcClient
        .sql(
            """
            insert into transaction (date, recurring_template_id, occurrence_date)
            values (:date, :templateId, :occurrenceDate)
            returning transaction_id
            """)
        .param("date", LocalDate.of(2026, 2, 28))
        .param("templateId", templateId)
        .param("occurrenceDate", occurrenceDate)
        .query(Long.class)
        .single();
  }

  @Test
  void transactionCarriesTheOccurrenceStamp() {
    long templateId =
        repository.insert(
            draft("Rent", bankAccountId, null, List.of(categoryLine("800"))), START.minusDays(1));

    long transactionId = stampedTransaction(templateId, LocalDate.of(2026, 2, 28));

    Long stampedTemplate =
        jdbcClient
            .sql("select recurring_template_id from transaction where transaction_id = :id")
            .param("id", transactionId)
            .query(Long.class)
            .single();
    assertThat(stampedTemplate).isEqualTo(templateId);
  }

  @Test
  void handEnteredTransactionsCarryNoStamp() {
    long first = stampedTransaction(null, null);
    long second = stampedTransaction(null, null); // the unique index lets unstamped rows repeat

    assertThat(second).isNotEqualTo(first);
  }

  @Test
  void stampIsBothOrNeither() {
    long templateId =
        repository.insert(
            draft("Rent", bankAccountId, null, List.of(categoryLine("800"))), START.minusDays(1));

    assertThatThrownBy(() -> stampedTransaction(templateId, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void occurrenceDateWithoutTemplateIsRefused() {
    assertThatThrownBy(() -> stampedTransaction(null, LocalDate.of(2026, 2, 28)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void oneOccurrenceBooksAtMostOneTransaction() {
    long templateId =
        repository.insert(
            draft("Rent", bankAccountId, null, List.of(categoryLine("800"))), START.minusDays(1));
    stampedTransaction(templateId, LocalDate.of(2026, 2, 28));

    assertThatThrownBy(() -> stampedTransaction(templateId, LocalDate.of(2026, 2, 28)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
