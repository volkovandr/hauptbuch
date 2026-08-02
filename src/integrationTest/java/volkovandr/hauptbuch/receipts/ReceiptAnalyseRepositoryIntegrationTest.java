package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.receipts.repository.ReceiptLineRepository;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;

/**
 * Integration tier (§1.5): row-mapping round-trips for the stage-9e analyse writes ({@link
 * ReceiptRepository} telemetry/header + {@link ReceiptLineRepository}) against real Postgres.
 * Flyway applies V12; each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptAnalyseRepositoryIntegrationTest {

  @Autowired ReceiptRepository receiptRepository;
  @Autowired ReceiptLineRepository receiptLineRepository;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

  private long preProcessed() {
    Receipt r = receiptRepository.insertCaptured("mobile", "originals/2026/07/a.jpg");
    receiptRepository.savePreProcess(r.receiptId(), "edited/2026/07/a.jpg", "{}", "note");
    return r.receiptId();
  }

  @Test
  void markProcessingClaimsOnlyPreProcessedReceipt() {
    long id = preProcessed();

    assertThat(receiptRepository.markProcessing(id)).isEqualTo(1);
    assertThat(receiptRepository.markProcessing(id)).isZero(); // already processing
    assertThat(receiptRepository.findById(id).orElseThrow().state()).isEqualTo("processing");
  }

  @Test
  void applyProcessedStoresTelemetryHeaderAndFlipsState() {
    long id = preProcessed();
    receiptRepository.markProcessing(id);
    ReceiptParseResult usage = new ReceiptParseResult("raw toon body", 1200, 300, 10, 20);
    ParsedHeader header =
        new ParsedHeader(
            "Rewe",
            "Berlin",
            "Germany",
            LocalDate.of(2026, 7, 21),
            LocalTime.of(12, 13),
            "4711",
            new BigDecimal("45.67"),
            "EUR",
            null);

    int rows = receiptRepository.applyProcessed(id, usage, new BigDecimal("0.006000"), header);

    assertThat(rows).isEqualTo(1);
    Receipt r = receiptRepository.findById(id).orElseThrow();
    assertThat(r.state()).isEqualTo("processed");
    assertThat(r.parseRaw()).isEqualTo("raw toon body");
    assertThat(r.tokensIn()).isEqualTo(1200);
    assertThat(r.tokensCacheRead()).isEqualTo(20);
    assertThat(r.parseCost()).isEqualByComparingTo("0.006000");
    assertThat(r.merchantText()).isEqualTo("Rewe");
    assertThat(r.merchantCity()).isEqualTo("Berlin");
    assertThat(r.receiptDate()).isEqualTo(LocalDate.of(2026, 7, 21));
    assertThat(r.receiptTime()).isEqualTo(LocalTime.of(12, 13));
    assertThat(r.receiptNumber()).isEqualTo("4711");
    assertThat(r.totalAmount()).isEqualByComparingTo("45.67");
    assertThat(r.currencyCode()).isEqualTo("EUR");
  }

  @Test
  void failUndecodableKeepsRawAndUsageButFails() {
    long id = preProcessed();
    receiptRepository.markProcessing(id);
    ReceiptParseResult usage = new ReceiptParseResult("garbage", 100, 0, 0, 0);

    receiptRepository.failUndecodable(id, "Could not decode", usage, new BigDecimal("0.0003"));

    Receipt r = receiptRepository.findById(id).orElseThrow();
    assertThat(r.state()).isEqualTo("failed");
    assertThat(r.parseError()).isEqualTo("Could not decode");
    assertThat(r.parseRaw()).isEqualTo("garbage");
    assertThat(r.tokensIn()).isEqualTo(100);
  }

  @Test
  void failTransportRecordsReasonWithNoBody() {
    long id = preProcessed();
    receiptRepository.markProcessing(id);

    receiptRepository.failTransport(id, "network down");

    Receipt r = receiptRepository.findById(id).orElseThrow();
    assertThat(r.state()).isEqualTo("failed");
    assertThat(r.parseError()).isEqualTo("network down");
    assertThat(r.parseRaw()).isNull();
  }

  @Test
  void retryReturnsFailedReceiptToPreProcessed() {
    long id = preProcessed();
    receiptRepository.markProcessing(id);
    receiptRepository.failTransport(id, "network down");

    assertThat(receiptRepository.retryToPreProcessed(id)).isEqualTo(1);
    Receipt r = receiptRepository.findById(id).orElseThrow();
    assertThat(r.state()).isEqualTo("pre_processed");
    assertThat(r.parseError()).isNull();
  }

  @Test
  void sweepFlipsOrphanedSingleModeProcessingToFailed() {
    long single = preProcessed();
    receiptRepository.markProcessing(single);

    int swept = receiptRepository.sweepOrphanedProcessing("Interrupted by a restart");

    assertThat(swept).isEqualTo(1);
    assertThat(receiptRepository.findById(single).orElseThrow().state()).isEqualTo("failed");
  }

  @Test
  void receiptLineInsertFindAndTagRoundTrip() {
    long id = preProcessed();
    long lineId =
        receiptLineRepository.insert(
            id,
            new ReceiptLineDraft(
                "2× Bread",
                new BigDecimal("1.40"),
                null,
                null,
                null,
                0,
                List.of(),
                "Food - Bread"));
    receiptLineRepository.insertTag(lineId, seedTag("Groceries"));

    List<ReceiptLine> lines = receiptLineRepository.findByReceiptId(id);

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).description()).isEqualTo("2× Bread");
    assertThat(lines.get(0).amount()).isEqualByComparingTo("1.40");
    assertThat(lines.get(0).aiTargetText()).isEqualTo("Food - Bread");
    assertThat(receiptLineRepository.findTagIds(lineId)).hasSize(1);
  }

  @Test
  void deleteByReceiptIdClearsLinesAndTags() {
    long id = preProcessed();
    long lineId =
        receiptLineRepository.insert(
            id, new ReceiptLineDraft("X", BigDecimal.ONE, null, null, null, 0, List.of(), null));
    receiptLineRepository.insertTag(lineId, seedTag("T"));

    receiptLineRepository.deleteByReceiptId(id);

    assertThat(receiptLineRepository.findByReceiptId(id)).isEmpty();
  }

  private long seedTag(String name) {
    return jdbcClient
        .sql("insert into tag (name, parent_id) values (:name, null) returning tag_id")
        .param("name", name)
        .query(Long.class)
        .single();
  }
}
