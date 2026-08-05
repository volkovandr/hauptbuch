package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import volkovandr.hauptbuch.categories.AiVocabularyService;
import volkovandr.hauptbuch.ledger.AiSettings;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Unit tier (plan §1.5): the batch worker's orchestration (stage 9h). {@code submit} and {@code
 * pollBatch} are driven directly (the executor and the scheduler are bypassed) to assert the claim
 * and skip counts, the batch-id write, the submit-failure path, and the distribution of a mixed
 * result set — including the halved cost and the leftover sweep. The network and DB are the mocked
 * seams.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceiptBatchAnalyserTest {

  private static final String BATCH_ID = "msgbatch_01";
  private static final long ONE = 1L;
  private static final long TWO = 2L;

  @Mock private ReceiptService receiptService;
  @Mock private ReceiptBatchClient batchClient;
  @Mock private ReceiptAnalyser receiptAnalyser;
  @Mock private ReceiptAnalysisService analysisService;
  @Mock private ReceiptPromptBuilder promptBuilder;
  @Mock private SettingsService settingsService;
  @Mock private AiVocabularyService aiVocabularyService;

  private ReceiptBatchAnalyser analyser() {
    return new ReceiptBatchAnalyser(
        receiptService,
        batchClient,
        receiptAnalyser,
        analysisService,
        promptBuilder,
        settingsService,
        aiVocabularyService);
  }

  /** Rates of 1 USD/MTok all round, so a cost is readable straight off the token counts. */
  private void stubSettings() {
    when(settingsService.aiConfig())
        .thenReturn(
            new AiSettings(
                "claude-sonnet-5",
                "key",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE));
  }

  private void stubReceipt(long id) {
    when(receiptService.findById(id)).thenReturn(Optional.of(receipt(id)));
    when(receiptService.editedBytes(id)).thenReturn(Optional.of(new byte[] {1, 2, 3}));
  }

  private static Receipt receipt(long id) {
    return new Receipt(
        id,
        "processing",
        null,
        "mobile",
        "orig.jpg",
        "edit.jpg",
        "{}",
        "note",
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
        null);
  }

  private static ReceiptParseResult body() {
    return new ReceiptParseResult("merchant:\n  name: Rewe\n", 1_000_000, 0, 0, 0);
  }

  // ── Claiming ────────────────────────────────────────────────────────────────

  @Test
  void startClaimsValidMembersAndSkipsTheRest() {
    when(analysisService.claim(ONE)).thenReturn(true);
    when(analysisService.claim(TWO)).thenReturn(false);

    assertThat(analyser().start(List.of(ONE, TWO))).isEqualTo(1);
  }

  @Test
  void startSubmitsNothingWhenNothingCouldBeClaimed() {
    when(analysisService.claim(anyLong())).thenReturn(false);

    assertThat(analyser().start(List.of(ONE, TWO))).isZero();
    verifyNoInteractions(batchClient);
  }

  // ── Submitting ──────────────────────────────────────────────────────────────

  @Test
  void submitSendsEveryMemberAndRecordsTheBatchId() {
    stubSettings();
    stubReceipt(ONE);
    stubReceipt(TWO);
    when(promptBuilder.build(any(), any())).thenReturn("system");
    when(promptBuilder.userText(anyString())).thenReturn("Parse this receipt. note");
    when(batchClient.submit(any())).thenReturn(BATCH_ID);

    analyser().submit(List.of(ONE, TWO));

    ArgumentCaptor<ReceiptBatchSubmission> sent =
        ArgumentCaptor.forClass(ReceiptBatchSubmission.class);
    verify(batchClient).submit(sent.capture());
    assertThat(sent.getValue().systemPrompt()).isEqualTo("system");
    assertThat(sent.getValue().model()).isEqualTo("claude-sonnet-5");
    assertThat(sent.getValue().items())
        .extracting(ReceiptBatchItem::receiptId)
        .containsExactly(ONE, TWO);
    verify(analysisService).assignBatch(List.of(ONE, TWO), BATCH_ID);
  }

  /**
   * A member whose baked image has gone missing is failed here, not left to strand in the batch.
   */
  @Test
  void submitFailsMembersWhoseEditedImageIsMissing() {
    stubSettings();
    stubReceipt(ONE);
    when(receiptService.findById(TWO)).thenReturn(Optional.of(receipt(TWO)));
    when(receiptService.editedBytes(TWO)).thenReturn(Optional.empty());
    when(batchClient.submit(any())).thenReturn(BATCH_ID);

    analyser().submit(List.of(ONE, TWO));

    verify(analysisService).failClaimed(List.of(TWO), "The edited image to analyse is missing");
    verify(analysisService).assignBatch(List.of(ONE), BATCH_ID);
  }

  @Test
  void submitFailureFailsEveryClaimedMember() {
    stubSettings();
    stubReceipt(ONE);
    stubReceipt(TWO);
    when(batchClient.submit(any()))
        .thenThrow(new ReceiptParseException("Batch submit failed: 429"));

    analyser().submit(List.of(ONE, TWO));

    verify(analysisService).failClaimed(List.of(ONE, TWO), "Batch submit failed: 429");
    verify(analysisService, never()).assignBatch(any(), anyString());
  }

  @Test
  void submitSurvivesAnUnexpectedError() {
    stubSettings();
    stubReceipt(ONE);
    when(batchClient.submit(any())).thenThrow(new IllegalStateException("boom"));

    analyser().submit(List.of(ONE));

    verify(analysisService).failClaimed(List.of(ONE), "Unexpected error: boom");
  }

  // ── Polling and distribution ────────────────────────────────────────────────

  @Test
  void pollLeavesRunningBatchAlone() {
    stubSettings();
    when(batchClient.poll(BATCH_ID, "key")).thenReturn(Optional.empty());

    analyser().pollBatch(BATCH_ID);

    verifyNoInteractions(receiptAnalyser);
    verify(analysisService, never()).failBatchMembers(anyString(), anyString());
  }

  /**
   * The whole point of the poller: a succeeded member goes through the same seeding path as single
   * mode, a failed one straight to {@code failed}, and whatever is left over is failed rather than
   * stuck.
   */
  @Test
  void pollDistributesMixedResultsAndSweepsTheLeftovers() {
    stubSettings();
    when(analysisService.batchMemberIds(BATCH_ID)).thenReturn(Set.of(ONE, TWO));
    when(batchClient.poll(BATCH_ID, "key"))
        .thenReturn(
            Optional.of(
                List.of(
                    ReceiptBatchOutcome.succeeded(ONE, body()),
                    ReceiptBatchOutcome.failed(TWO, "The batch expired"))));

    analyser().pollBatch(BATCH_ID);

    verify(receiptAnalyser)
        .applyParsed(eq(ONE), any(), any(), eq("Could not decode the parser response"));
    verify(analysisService).failTransport(TWO, "The batch expired");
    verify(analysisService)
        .failBatchMembers(BATCH_ID, "The batch returned no result for this receipt");
  }

  /** The 50 % batch discount is Anthropic's pricing rule, applied to the whole computed cost. */
  @Test
  void batchMemberCostIsHalved() {
    stubSettings();
    when(analysisService.batchMemberIds(BATCH_ID)).thenReturn(Set.of(ONE));
    when(batchClient.poll(BATCH_ID, "key"))
        .thenReturn(Optional.of(List.of(ReceiptBatchOutcome.succeeded(ONE, body()))));

    analyser().pollBatch(BATCH_ID);

    ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
    verify(receiptAnalyser).applyParsed(eq(ONE), any(), cost.capture(), anyString());
    // 1M input tokens at 1 USD/MTok = 1.00, halved.
    assertThat(cost.getValue()).isEqualByComparingTo("0.500000");
  }

  /**
   * A receipt that left the batch between submit and result — soft-deleted, or retried and
   * re-analysed — is not written to. Its parse was still billed; the operator meant to move on.
   */
  @Test
  void resultForReceiptNoLongerInTheBatchIsAbandoned() {
    stubSettings();
    when(analysisService.batchMemberIds(BATCH_ID)).thenReturn(Set.of(TWO));
    when(batchClient.poll(BATCH_ID, "key"))
        .thenReturn(Optional.of(List.of(ReceiptBatchOutcome.succeeded(ONE, body()))));

    analyser().pollBatch(BATCH_ID);

    verifyNoInteractions(receiptAnalyser);
    verify(analysisService, never()).failTransport(anyLong(), anyString());
  }

  @Test
  void pollFailureFailsEveryMemberOfTheBatch() {
    stubSettings();
    when(batchClient.poll(BATCH_ID, "key"))
        .thenThrow(new ReceiptParseException("Batch poll failed: 404"));

    analyser().pollBatch(BATCH_ID);

    verify(analysisService).failBatchMembers(BATCH_ID, "Batch poll failed: 404");
    verifyNoInteractions(receiptAnalyser);
  }

  @Test
  void pollSurvivesAnUnexpectedError() {
    stubSettings();
    when(batchClient.poll(BATCH_ID, "key")).thenThrow(new IllegalStateException("boom"));

    analyser().pollBatch(BATCH_ID);

    verify(analysisService).failBatchMembers(BATCH_ID, "Unexpected error: boom");
  }

  @Test
  void scheduledTickPollsEveryLiveBatch() {
    stubSettings();
    when(analysisService.liveBatchIds()).thenReturn(List.of(BATCH_ID, "msgbatch_02"));
    when(batchClient.poll(anyString(), anyString())).thenReturn(Optional.empty());

    analyser().pollLiveBatches();

    verify(batchClient).poll(BATCH_ID, "key");
    verify(batchClient).poll("msgbatch_02", "key");
  }
}
