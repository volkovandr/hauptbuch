package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;

/**
 * Integration tier (§1.5): row-mapping round-trips for {@link ReceiptRepository} against real
 * Postgres. Flyway applies V9; each test is rolled back. The register/mobile selects are exercised
 * here (plain filtered selects, not SQL-resident logic — CLAUDE.md §6).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptRepositoryIntegrationTest {

  @Autowired ReceiptRepository receiptRepository;
  @Autowired JdbcClient jdbcClient;

  @Test
  void insertCapturedAndFindByIdRoundTrip() {
    Receipt inserted = receiptRepository.insertCaptured("mobile", "originals/2026/07/a.jpg");

    assertThat(inserted.receiptId()).isNotNull();
    assertThat(inserted.state()).isEqualTo("new");
    assertThat(inserted.source()).isEqualTo("mobile");
    assertThat(inserted.originalPath()).isEqualTo("originals/2026/07/a.jpg");
    assertThat(inserted.capturedAt()).isNotNull();
    assertThat(inserted.editedPath()).isNull();
    assertThat(inserted.transactionId()).isNull();
    assertThat(inserted.deletedAt()).isNull();

    Optional<Receipt> found = receiptRepository.findById(inserted.receiptId());
    assertThat(found).contains(inserted);
  }

  @Test
  void findByIdReturnsEmptyForSoftDeleted() {
    Receipt r = receiptRepository.insertCaptured("mobile", "originals/2026/07/a.jpg");

    receiptRepository.softDelete(r.receiptId());

    assertThat(receiptRepository.findById(r.receiptId())).isEmpty();
  }

  @Test
  void savePreProcessRecordsTheEditAndMovesToPreProcessed() {
    Receipt r = receiptRepository.insertCaptured("pc", "originals/2026/07/a.jpg");

    receiptRepository.savePreProcess(
        r.receiptId(), "edited/2026/07/a.jpg", "{\"rotate\":90}", "this is fuel");

    assertThat(receiptRepository.findById(r.receiptId()))
        .get()
        .satisfies(
            saved -> {
              assertThat(saved.state()).isEqualTo("pre_processed");
              assertThat(saved.editedPath()).isEqualTo("edited/2026/07/a.jpg");
              assertThat(saved.editRecipe()).isEqualTo("{\"rotate\":90}");
              assertThat(saved.aiNote()).isEqualTo("this is fuel");
            });
  }

  @Test
  void discardEditsClearsTheEditButKeepsTheAiNoteAndReturnsToNew() {
    Receipt r = receiptRepository.insertCaptured("pc", "originals/2026/07/a.jpg");
    receiptRepository.savePreProcess(
        r.receiptId(), "edited/2026/07/a.jpg", "{\"rotate\":90}", "this is fuel");

    receiptRepository.discardEdits(r.receiptId());

    assertThat(receiptRepository.findById(r.receiptId()))
        .get()
        .satisfies(
            saved -> {
              assertThat(saved.state()).isEqualTo("new");
              assertThat(saved.editedPath()).isNull();
              assertThat(saved.editRecipe()).isNull();
              // The AI note survives the stage-undo — it describes the receipt, not the pixels.
              assertThat(saved.aiNote()).isEqualTo("this is fuel");
            });
  }

  @Test
  void registerFilterHonoursStateSetAndExcludesSoftDeleted() {
    long keep = capturedWithState("new");
    long other = capturedWithState("committed");
    long deleted = capturedWithState("new");
    receiptRepository.softDelete(deleted);

    List<Long> ids =
        receiptRepository.findForRegister(List.of("new"), null, false, true).stream()
            .map(Receipt::receiptId)
            .toList();

    assertThat(ids).contains(keep).doesNotContain(other, deleted);
  }

  @Test
  void registerFilterAppliesTheCaptureDateLowerBound() {
    // Midday captures so a reasonable session-timezone offset can't shift them across a day
    // boundary — the `from` bound is a calendar day interpreted in the DB session zone.
    long onBound = capturedAt("2026-07-10T12:00:00Z", "new");
    long after = capturedAt("2026-07-15T12:00:00Z", "new");
    long tooEarly = capturedAt("2026-07-05T12:00:00Z", "new");

    List<Long> ids =
        receiptRepository
            .findForRegister(List.of("new"), LocalDate.parse("2026-07-10"), false, true)
            .stream()
            .map(Receipt::receiptId)
            .toList();

    assertThat(ids).contains(onBound, after).doesNotContain(tooEarly);
  }

  @Test
  void registerListIsCapturedDescendingByDefault() {
    long later = capturedAt("2026-07-20T10:00:00Z", "new");
    long earlier = capturedAt("2026-07-10T10:00:00Z", "new");

    List<Long> ids =
        receiptRepository.findForRegister(List.of("new"), null, false, true).stream()
            .map(Receipt::receiptId)
            .toList();

    assertThat(ids).containsSubsequence(later, earlier);
  }

  // ── Sortable headers (issue tracker #11) ──────────────────────────────────

  @Test
  void findForRegisterOrdersByCapturedAscendingWhenNotDescending() {
    long later = capturedAt("2026-07-20T10:00:00Z", "new");
    long earlier = capturedAt("2026-07-10T10:00:00Z", "new");

    List<Long> ids =
        receiptRepository.findForRegister(List.of("new"), null, false, false).stream()
            .map(Receipt::receiptId)
            .toList();

    assertThat(ids).containsSubsequence(earlier, later);
  }

  @Test
  void findForRegisterOrdersByTotalWhenSortByTotalIsTrue() {
    long small = capturedWithTotal("new", "5.00");
    long large = capturedWithTotal("new", "50.00");

    List<Long> descending =
        receiptRepository.findForRegister(List.of("new"), null, true, true).stream()
            .map(Receipt::receiptId)
            .toList();
    List<Long> ascending =
        receiptRepository.findForRegister(List.of("new"), null, true, false).stream()
            .map(Receipt::receiptId)
            .toList();

    assertThat(descending).containsSubsequence(large, small);
    assertThat(ascending).containsSubsequence(small, large);
  }

  @Test
  void findForRegisterPlacesNullTotalsLastAscendingAndFirstDescending() {
    long noTotal = capturedWithState("new");
    long withTotal = capturedWithTotal("new", "5.00");

    List<Long> ascending =
        receiptRepository.findForRegister(List.of("new"), null, true, false).stream()
            .map(Receipt::receiptId)
            .toList();
    List<Long> descending =
        receiptRepository.findForRegister(List.of("new"), null, true, true).stream()
            .map(Receipt::receiptId)
            .toList();

    assertThat(ascending).containsSubsequence(withTotal, noTotal);
    assertThat(descending).containsSubsequence(noTotal, withTotal);
  }

  @Test
  void findForRegisterBreaksTotalTiesByCapturedThenReceiptId() {
    long earlierSameTotal = capturedAtWithTotal("2026-07-10T10:00:00Z", "new", "5.00");
    long laterSameTotal = capturedAtWithTotal("2026-07-20T10:00:00Z", "new", "5.00");

    List<Long> descending =
        receiptRepository.findForRegister(List.of("new"), null, true, true).stream()
            .map(Receipt::receiptId)
            .toList();

    // Same total; ties break by captured date in the same (descending) direction as the primary
    // sort.
    assertThat(descending).containsSubsequence(laterSameTotal, earlierSameTotal);
  }

  @Test
  void mobileListIsCapturedDescendingWithinTheWindowAndIncludesCommitted() {
    long committed = capturedAt("2026-07-20T10:00:00Z", "committed");
    long fresh = capturedAt("2026-07-25T10:00:00Z", "new");
    long ancient = capturedAt("2026-01-01T10:00:00Z", "new");

    OffsetDateTime since = OffsetDateTime.parse("2026-07-01T00:00:00Z");
    List<Long> ids =
        receiptRepository.findForMobile(since).stream().map(Receipt::receiptId).toList();

    // Newest first; committed included; the out-of-window one excluded.
    assertThat(ids).containsSubsequence(fresh, committed).doesNotContain(ancient);
  }

  @Test
  void saveEditorHeaderRoundTripsTheNoteAndReceiptNumber() {
    Receipt r = receiptRepository.insertCaptured("pc", "originals/2026/08/a.jpg");

    receiptRepository.saveEditorHeader(
        r.receiptId(),
        new ReceiptHeaderDraft(
            LocalDate.parse("2026-08-03"),
            null,
            null,
            "EUR",
            new BigDecimal("42.14"),
            "Company car",
            "BEL-4711"));

    Receipt saved = receiptRepository.findById(r.receiptId()).orElseThrow();
    assertThat(saved.receiptDate()).isEqualTo(LocalDate.parse("2026-08-03"));
    assertThat(saved.currencyCode()).isEqualTo("EUR");
    assertThat(saved.totalAmount()).isEqualByComparingTo("42.14");
    assertThat(saved.note()).isEqualTo("Company car"); // the V15 column
    assertThat(saved.receiptNumber()).isEqualTo("BEL-4711");
  }

  @Test
  void markCommittedLinksTheTransactionAndReopenReturnsToProcessed() {
    long id = capturedWithState("processed");
    long transactionId = insertTransaction();

    assertThat(receiptRepository.markCommitted(id, transactionId)).isEqualTo(1);
    Receipt committed = receiptRepository.findById(id).orElseThrow();
    assertThat(committed.state()).isEqualTo("committed");
    assertThat(committed.transactionId()).isEqualTo(transactionId);

    assertThat(receiptRepository.reopen(id)).isEqualTo(1);
    Receipt reopened = receiptRepository.findById(id).orElseThrow();
    assertThat(reopened.state()).isEqualTo("processed");
    // Reopen writes nothing but the state — the link is what makes the next Confirm a Re-enter.
    assertThat(reopened.transactionId()).isEqualTo(transactionId);
  }

  /** A bare transaction row to point a receipt's FK at (its legs are irrelevant here). */
  private long insertTransaction() {
    return jdbcClient
        .sql(
            "insert into transaction (date, lifecycle) values (:d, 'confirmed')"
                + " returning transaction_id")
        .param("d", LocalDate.parse("2026-08-03"))
        .query(Long.class)
        .single();
  }

  @Test
  void reopenTouchesNothingButCommittedReceipts() {
    long id = capturedWithState("processed");

    assertThat(receiptRepository.reopen(id)).isZero();
  }

  // ── Batch membership (plan §9h) ──────────────────────────────────────────────

  @Test
  void assignBatchStampsEveryMemberAndFindsThemBack() {
    long one = capturedWithState("processing");
    long two = capturedWithState("processing");

    assertThat(receiptRepository.assignBatch(List.of(one, two), "msgbatch_01")).isEqualTo(2);

    assertThat(receiptRepository.findById(one).orElseThrow().batchId()).isEqualTo("msgbatch_01");
    assertThat(receiptRepository.findLiveBatchIds()).containsExactly("msgbatch_01");
    assertThat(receiptRepository.findLiveBatchMembers("msgbatch_01"))
        .extracting(Receipt::receiptId)
        .containsExactly(one, two);
  }

  @Test
  void assignBatchIgnoresEmptySelection() {
    assertThat(receiptRepository.assignBatch(List.of(), "msgbatch_01")).isZero();
  }

  /**
   * The poller's work list is the live, still-{@code processing} members only: a landed member and
   * a soft-deleted one both drop out — which is exactly how a batch stops being polled.
   */
  @Test
  void liveBatchLookupsSkipLandedAndDeletedMembers() {
    long processing = capturedWithState("processing");
    long landed = capturedWithState("processed");
    long deleted = capturedWithState("processing");
    receiptRepository.assignBatch(List.of(processing, landed, deleted), "msgbatch_01");
    receiptRepository.softDelete(deleted);

    assertThat(receiptRepository.findLiveBatchMembers("msgbatch_01"))
        .extracting(Receipt::receiptId)
        .containsExactly(processing);

    receiptRepository.failBatchMembers("msgbatch_01", "gone");
    assertThat(receiptRepository.findLiveBatchIds()).isEmpty();
  }

  @Test
  void failBatchMembersFailsOnlyTheProcessingOnes() {
    long processing = capturedWithState("processing");
    long landed = capturedWithState("processed");
    receiptRepository.assignBatch(List.of(processing, landed), "msgbatch_01");

    assertThat(receiptRepository.failBatchMembers("msgbatch_01", "batch expired")).isEqualTo(1);

    Receipt failed = receiptRepository.findById(processing).orElseThrow();
    assertThat(failed.state()).isEqualTo("failed");
    assertThat(failed.parseError()).isEqualTo("batch expired");
    // The landed member keeps its state — a result already applied is not rolled back.
    assertThat(receiptRepository.findById(landed).orElseThrow().state()).isEqualTo("processed");
  }

  /** A submit that never reached the API has no batch id yet, so its members fail by id. */
  @Test
  void failClaimedFailsTheClaimedReceipts() {
    long claimed = capturedWithState("processing");
    long untouched = capturedWithState("pre_processed");

    assertThat(receiptRepository.failClaimed(List.of(claimed, untouched), "submit failed"))
        .isEqualTo(1);

    assertThat(receiptRepository.findById(claimed).orElseThrow().state()).isEqualTo("failed");
    assertThat(receiptRepository.findById(untouched).orElseThrow().state())
        .isEqualTo("pre_processed");
  }

  @Test
  void failClaimedIgnoresEmptySelection() {
    assertThat(receiptRepository.failClaimed(List.of(), "submit failed")).isZero();
  }

  /**
   * A retried batch member leaves its batch behind. Without this the dead batch would stay on the
   * poller's list, and the register would keep badging the receipt as a batch member.
   */
  @Test
  void retryClearsTheBatchId() {
    long id = capturedWithState("processing");
    receiptRepository.assignBatch(List.of(id), "msgbatch_01");
    receiptRepository.failBatchMembers("msgbatch_01", "batch expired");

    assertThat(receiptRepository.retryToPreProcessed(id)).isEqualTo(1);

    assertThat(receiptRepository.findById(id).orElseThrow().batchId()).isNull();
    assertThat(receiptRepository.findLiveBatchIds()).isEmpty();
  }

  /**
   * Every claim starts single-mode. A previously-batched receipt analysed again on its own must not
   * keep pointing at the old batch — that would exempt it from the startup sweep and let the poller
   * fail a live single parse when the dead batch 404s.
   */
  @Test
  void claimingClearsStaleBatchId() {
    long id = capturedWithState("processing");
    receiptRepository.assignBatch(List.of(id), "msgbatch_01");
    receiptRepository.failBatchMembers("msgbatch_01", "batch expired");
    receiptRepository.retryToPreProcessed(id);

    assertThat(receiptRepository.markProcessing(id)).isEqualTo(1);

    Receipt claimed = receiptRepository.findById(id).orElseThrow();
    assertThat(claimed.state()).isEqualTo("processing");
    assertThat(claimed.batchId()).isNull();
    // …and being single-mode again, it is back in the startup sweep's scope.
    assertThat(receiptRepository.sweepOrphanedProcessing("restarted")).isEqualTo(1);
  }

  /** A batch member is exempt from the 9e startup sweep — the poller resumes it after a restart. */
  @Test
  void startupSweepLeavesBatchMembersAlone() {
    long single = capturedWithState("processing");
    long member = capturedWithState("processing");
    receiptRepository.assignBatch(List.of(member), "msgbatch_01");

    assertThat(receiptRepository.sweepOrphanedProcessing("restarted")).isEqualTo(1);

    assertThat(receiptRepository.findById(single).orElseThrow().state()).isEqualTo("failed");
    assertThat(receiptRepository.findById(member).orElseThrow().state()).isEqualTo("processing");
  }

  /** Insert a receipt in a given state at "now", returning its id. */
  private long capturedWithState(String state) {
    Receipt r = receiptRepository.insertCaptured("pc", "originals/2026/07/x.jpg");
    jdbcClient
        .sql("update receipt set state = :state where receipt_id = :id")
        .param("state", state)
        .param("id", r.receiptId())
        .update();
    return r.receiptId();
  }

  /** Insert a receipt at a crafted capture instant and state (bypassing the now() default). */
  private long capturedAt(String instant, String state) {
    return jdbcClient
        .sql(
            """
            insert into receipt (state, source, original_path, captured_at)
            values (:state, 'pc', 'originals/2026/07/x.jpg', :capturedAt)
            returning receipt_id
            """)
        .param("state", state)
        .param(
            "capturedAt",
            OffsetDateTime.parse(instant).atZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime())
        .query(Long.class)
        .single();
  }

  /** Insert a receipt in a given state at "now" with a parsed total, returning its id. */
  private long capturedWithTotal(String state, String total) {
    long id = capturedWithState(state);
    jdbcClient
        .sql("update receipt set total_amount = :total where receipt_id = :id")
        .param("total", new BigDecimal(total))
        .param("id", id)
        .update();
    return id;
  }

  /** Insert a receipt at a crafted capture instant, state, and parsed total. */
  private long capturedAtWithTotal(String instant, String state, String total) {
    long id = capturedAt(instant, state);
    jdbcClient
        .sql("update receipt set total_amount = :total where receipt_id = :id")
        .param("total", new BigDecimal(total))
        .param("id", id)
        .update();
    return id;
  }
}
