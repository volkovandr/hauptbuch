package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

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
  void updateStateMovesTheReceipt() {
    Receipt r = receiptRepository.insertCaptured("pc", "originals/2026/07/a.jpg");

    receiptRepository.updateState(r.receiptId(), "discarded");

    assertThat(receiptRepository.findById(r.receiptId()))
        .get()
        .extracting(Receipt::state)
        .isEqualTo("discarded");
  }

  @Test
  void registerFilterHonoursStateSetAndExcludesSoftDeleted() {
    long keep = capturedWithState("new");
    long other = capturedWithState("committed");
    long deleted = capturedWithState("new");
    receiptRepository.softDelete(deleted);

    List<Long> ids =
        receiptRepository.findForRegister(List.of("new"), null).stream()
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
        receiptRepository.findForRegister(List.of("new"), LocalDate.parse("2026-07-10")).stream()
            .map(Receipt::receiptId)
            .toList();

    assertThat(ids).contains(onBound, after).doesNotContain(tooEarly);
  }

  @Test
  void registerListIsCapturedAscending() {
    long later = capturedAt("2026-07-20T10:00:00Z", "new");
    long earlier = capturedAt("2026-07-10T10:00:00Z", "new");

    List<Long> ids =
        receiptRepository.findForRegister(List.of("new"), null).stream()
            .map(Receipt::receiptId)
            .toList();

    assertThat(ids).containsSubsequence(earlier, later);
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

  /** Insert a receipt in a given state at "now", returning its id. */
  private long capturedWithState(String state) {
    Receipt r = receiptRepository.insertCaptured("pc", "originals/2026/07/x.jpg");
    receiptRepository.updateState(r.receiptId(), state);
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
}
