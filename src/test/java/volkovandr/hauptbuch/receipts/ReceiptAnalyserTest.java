package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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
 * Unit tier (plan §1.5): the analyse worker's orchestration (stage 9e). Drives {@code run}
 * synchronously (the executor is bypassed) to assert each outcome path — processed, transport
 * failure, undecodable failure, abandon-when-deleted, missing image — and the claim gating on
 * {@code start} and the startup sweep. The network and DB are the mocked seams.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceiptAnalyserTest {

  private static final long ID = 5L;

  @Mock private ReceiptService receiptService;
  @Mock private ReceiptParser receiptParser;
  @Mock private ReceiptPromptBuilder promptBuilder;
  @Mock private ToonReceiptDecoder decoder;
  @Mock private ToonRepair toonRepair;
  @Mock private ReceiptSeeder seeder;
  @Mock private ReceiptAnalysisService analysisService;
  @Mock private SettingsService settingsService;
  @Mock private AiVocabularyService aiVocabularyService;

  @BeforeEach
  void repairChangesNothingByDefault() {
    when(toonRepair.repair(any()))
        .thenAnswer(call -> new ToonRepair.Result(call.getArgument(0), List.of(), List.of()));
  }

  private ReceiptAnalyser analyser() {
    return new ReceiptAnalyser(
        receiptService,
        receiptParser,
        promptBuilder,
        decoder,
        toonRepair,
        seeder,
        analysisService,
        settingsService,
        aiVocabularyService);
  }

  private static Receipt receipt() {
    return new Receipt(
        ID,
        "processing",
        null,
        "mobile",
        "orig.jpg",
        "edit.jpg",
        "{}",
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
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private void stubUpToParser() {
    when(receiptService.findById(ID)).thenReturn(Optional.of(receipt()));
    when(receiptService.editedBytes(ID)).thenReturn(Optional.of(new byte[] {1, 2, 3}));
    when(settingsService.aiConfig())
        .thenReturn(new AiSettings("claude-sonnet-5", "key", null, null, null, null));
    when(promptBuilder.build(any(), any())).thenReturn("system");
    when(promptBuilder.userText(any())).thenReturn("Parse this receipt.");
  }

  private static Receipt parsedReceipt(String state, String rawParse) {
    return parsedReceipt(state, rawParse, null);
  }

  private static Receipt parsedReceipt(String state, String rawParse, Long transactionId) {
    return new Receipt(
        ID,
        state,
        null,
        "mobile",
        "orig.jpg",
        "edit.jpg",
        "{}",
        null,
        null,
        null,
        rawParse,
        null,
        null,
        null,
        null,
        null,
        null,
        transactionId,
        null,
        "was bad",
        10,
        5,
        0,
        0,
        new BigDecimal("0.006"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** The same receipt, already backing a booked transaction — the reopened shape (9g). */
  private static Receipt bookedReceipt(String state, String rawParse) {
    return parsedReceipt(state, rawParse, 77L);
  }

  @Test
  void processesOnDecodableSuccess() {
    stubUpToParser();
    ReceiptParseResult result = new ReceiptParseResult("raw", 10, 5, 0, 0);
    when(receiptParser.parse(any(), any())).thenReturn(result);
    ParsedReceipt parsed = new ParsedReceipt(null, null, List.of());
    when(decoder.decode("raw")).thenReturn(Optional.of(parsed));
    SeededReceipt seeded =
        new SeededReceipt(
            new ParsedHeader(null, null, null, null, null, null, null, null, null), List.of());
    when(seeder.seed(parsed)).thenReturn(seeded);

    analyser().run(ID, false);

    verify(analysisService).applyProcessed(eq(ID), eq(result), any(BigDecimal.class), eq(seeded));
  }

  @Test
  void failsTransportOnParseException() {
    stubUpToParser();
    when(receiptParser.parse(any(), any())).thenThrow(new ReceiptParseException("network down"));

    analyser().run(ID, false);

    verify(analysisService).failTransport(eq(ID), any());
    verify(analysisService, never()).applyProcessed(anyLong(), any(), any(), any());
  }

  @Test
  void failsUndecodableWhenDecodeEmpty() {
    stubUpToParser();
    ReceiptParseResult result = new ReceiptParseResult("garbage", 10, 5, 0, 0);
    when(receiptParser.parse(any(), any())).thenReturn(result);
    when(decoder.decode("garbage")).thenReturn(Optional.empty());

    analyser().run(ID, false);

    verify(analysisService).failUndecodable(eq(ID), any(), eq(result), any(BigDecimal.class));
  }

  @Test
  void abandonsReceiptDeletedBeforeRun() {
    when(receiptService.findById(ID)).thenReturn(Optional.empty());

    analyser().run(ID, false);

    verifyNoInteractions(receiptParser);
    verify(analysisService, never()).applyProcessed(anyLong(), any(), any(), any());
    verify(analysisService, never()).failTransport(anyLong(), any());
  }

  @Test
  void failsWhenEditedImageMissing() {
    when(receiptService.findById(ID)).thenReturn(Optional.of(receipt()));
    when(receiptService.editedBytes(ID)).thenReturn(Optional.empty());

    analyser().run(ID, false);

    verify(analysisService).failTransport(eq(ID), any());
    verifyNoInteractions(receiptParser);
  }

  @Test
  void startClaimsBeforeQueuing() {
    when(analysisService.claim(ID)).thenReturn(false);

    assertThat(analyser().start(ID, false)).isFalse();
  }

  /**
   * The 9h cache flag reaches the parser verbatim — plain Analyse leaves the prefix unmarked, so a
   * one-off parse never pays the cache write.
   */
  @Test
  void plainAnalyseAsksForNoPromptCache() {
    stubUpToParser();
    when(receiptParser.parse(any(), any())).thenThrow(new ReceiptParseException("stop here"));

    analyser().run(ID, false);

    assertThat(capturedRequest().cachePrompt()).isFalse();
  }

  @Test
  void cachedAnalyseMarksThePromptPrefix() {
    stubUpToParser();
    when(receiptParser.parse(any(), any())).thenThrow(new ReceiptParseException("stop here"));

    analyser().run(ID, true);

    ReceiptParseRequest request = capturedRequest();
    assertThat(request.cachePrompt()).isTrue();
    // Only the system prompt is the cacheable prefix; the note stays in the volatile user turn.
    assertThat(request.systemPrompt()).isEqualTo("system");
    assertThat(request.userText()).isEqualTo("Parse this receipt.");
  }

  private ReceiptParseRequest capturedRequest() {
    ArgumentCaptor<ReceiptParseRequest> sent = ArgumentCaptor.forClass(ReceiptParseRequest.class);
    verify(receiptParser).parse(sent.capture(), any());
    return sent.getValue();
  }

  @Test
  void reparseProcessesEditedTextWithoutCallingTheApi() {
    when(receiptService.findById(ID))
        .thenReturn(Optional.of(parsedReceipt(ReceiptState.FAILED, "bad,toon")));
    ParsedReceipt parsed = new ParsedReceipt(null, null, List.of());
    when(decoder.decode("fixed \"good, toon\"")).thenReturn(Optional.of(parsed));
    SeededReceipt seeded =
        new SeededReceipt(
            new ParsedHeader(null, null, null, null, null, null, null, null, null), List.of());
    when(seeder.seed(parsed)).thenReturn(seeded);

    assertThat(analyser().reparse(ID, "fixed \"good, toon\"")).isTrue();

    // No new request is sent; the already-billed usage/cost is preserved on the re-seed.
    verifyNoInteractions(receiptParser);
    verify(analysisService)
        .applyProcessed(
            eq(ID),
            eq(new ReceiptParseResult("fixed \"good, toon\"", "bad,toon", 10, 5, 0, 0)),
            eq(new BigDecimal("0.006")),
            eq(seeded));
  }

  @Test
  void reparseKeepsFailedWhenEditedTextStillUndecodable() {
    when(receiptService.findById(ID))
        .thenReturn(Optional.of(parsedReceipt(ReceiptState.FAILED, "bad,toon")));
    when(decoder.decode("still bad")).thenReturn(Optional.empty());

    assertThat(analyser().reparse(ID, "still bad")).isFalse();

    verify(analysisService).failUndecodable(eq(ID), any(), any(), any());
    verify(analysisService, never()).applyProcessed(anyLong(), any(), any(), any());
  }

  /**
   * Issue tracker receipt-processing/19: a {@code processed} receipt re-seeds from its stored
   * response too, so a response that named a tag which did not exist at analysis time can be
   * applied once the tag does — without paying for the parse again.
   */
  @Test
  void reparseReSeedsProcessedReceiptWithoutCallingTheApi() {
    when(receiptService.findById(ID))
        .thenReturn(Optional.of(parsedReceipt(ReceiptState.PROCESSED, "tags: Trips:France-2026")));
    ParsedReceipt parsed = new ParsedReceipt(null, null, List.of());
    when(decoder.decode("tags: Trips:France-2026")).thenReturn(Optional.of(parsed));
    SeededReceipt seeded =
        new SeededReceipt(
            new ParsedHeader(null, null, null, null, null, null, null, null, null), List.of());
    when(seeder.seed(parsed)).thenReturn(seeded);

    assertThat(analyser().reparse(ID, "tags: Trips:France-2026")).isTrue();

    verifyNoInteractions(receiptParser);
    verify(analysisService)
        .applyProcessed(
            eq(ID),
            eq(new ReceiptParseResult("tags: Trips:France-2026", 10, 5, 0, 0)),
            eq(new BigDecimal("0.006")),
            eq(seeded));
  }

  /** A committed receipt's lines back a real transaction — re-seeding must not touch them. */
  @Test
  void reparseIgnoresCommittedReceipt() {
    when(receiptService.findById(ID))
        .thenReturn(Optional.of(parsedReceipt(ReceiptState.COMMITTED, "good toon")));

    assertThat(analyser().reparse(ID, "anything")).isFalse();

    verifyNoInteractions(decoder);
    verify(analysisService, never()).applyProcessed(anyLong(), any(), any(), any());
    verify(analysisService, never()).failUndecodable(anyLong(), any(), any(), any());
  }

  /**
   * A blank submit (the operator cleared the textarea, or the param never arrived) would decode to
   * nothing and land the receipt in {@code failed} with {@code parse_raw} emptied — destroying the
   * only copy of a response that cannot be got back without paying for the parse again.
   */
  @Test
  void reparseIgnoresBlankEditedTextRatherThanWipingTheStoredResponse() {
    assertThat(analyser().reparse(ID, "   ")).isFalse();
    assertThat(analyser().reparse(ID, null)).isFalse();

    verifyNoInteractions(receiptService, decoder);
    verify(analysisService, never()).applyProcessed(anyLong(), any(), any(), any());
    verify(analysisService, never()).failUndecodable(anyLong(), any(), any(), any());
  }

  /**
   * A reopened receipt is {@code processed} again but still backs a live transaction, and an
   * undecodable edit would strand it in {@code failed} — away from the transaction link and the
   * transaction-aware delete rung. Re-seeding is refused while the link stands.
   */
  @Test
  void reparseIgnoresReopenedReceiptStillLinkedToTransaction() {
    when(receiptService.findById(ID))
        .thenReturn(Optional.of(bookedReceipt(ReceiptState.PROCESSED, "good toon")));

    assertThat(analyser().reparse(ID, "anything")).isFalse();

    verifyNoInteractions(decoder);
    verify(analysisService, never()).applyProcessed(anyLong(), any(), any(), any());
    verify(analysisService, never()).failUndecodable(anyLong(), any(), any(), any());
  }

  @Test
  void reparseIgnoresReceiptThatIsNeitherFailedNorProcessed() {
    when(receiptService.findById(ID)).thenReturn(Optional.of(receipt())); // state = processing

    assertThat(analyser().reparse(ID, "anything")).isFalse();

    verifyNoInteractions(decoder);
    verify(analysisService, never()).applyProcessed(anyLong(), any(), any(), any());
    verify(analysisService, never()).failUndecodable(anyLong(), any(), any(), any());
  }

  @Test
  void sweepsOrphansOnStartup() {
    when(analysisService.sweepOrphans(any())).thenReturn(2);

    analyser().sweepOrphansOnStartup();

    verify(analysisService).sweepOrphans(any());
  }

  // ── receipt-processing/34: auto-repair and the original response ───────────

  private SeededReceipt stubSeed(ParsedReceipt parsed) {
    SeededReceipt seeded =
        new SeededReceipt(
            new ParsedHeader(null, null, null, null, null, null, null, null, null), List.of());
    when(seeder.seed(parsed)).thenReturn(seeded);
    return seeded;
  }

  @Test
  void freshResponseThatOnlyDecodesAfterRepairIsStoredRepairedWithTheOriginalKept() {
    stubUpToParser();
    when(receiptParser.parse(any(), any()))
        .thenReturn(new ReceiptParseResult("bad,toon", 10, 5, 0, 0));
    when(decoder.decode("bad,toon")).thenReturn(Optional.empty());
    when(toonRepair.repair("bad,toon"))
        .thenReturn(new ToonRepair.Result("good toon", List.of("quoted"), List.of()));
    ParsedReceipt parsed = new ParsedReceipt(null, null, List.of());
    when(decoder.decode("good toon")).thenReturn(Optional.of(parsed));
    SeededReceipt seeded = stubSeed(parsed);

    analyser().run(ID, false);

    verify(analysisService)
        .applyProcessed(
            eq(ID),
            eq(new ReceiptParseResult("good toon", "bad,toon", 10, 5, 0, 0)),
            any(BigDecimal.class),
            eq(seeded));
    verify(receiptParser, times(1)).parse(any(), any()); // a re-decode, never a second API call
  }

  @Test
  void freshResponseThatStaysUndecodableAfterRepairKeepsTheModelsTextAndFails() {
    stubUpToParser();
    ReceiptParseResult result = new ReceiptParseResult("bad,toon", 10, 5, 0, 0);
    when(receiptParser.parse(any(), any())).thenReturn(result);
    when(decoder.decode(any())).thenReturn(Optional.empty());
    when(toonRepair.repair("bad,toon"))
        .thenReturn(new ToonRepair.Result("half fixed", List.of("quoted"), List.of()));

    analyser().run(ID, false);

    verify(analysisService).failUndecodable(eq(ID), any(), eq(result), any(BigDecimal.class));
    verify(receiptParser, times(1)).parse(any(), any());
  }

  @Test
  void cleanFreshResponseIsStoredUntouchedWithoutAnOriginal() {
    stubUpToParser();
    ReceiptParseResult result = new ReceiptParseResult("good toon", 10, 5, 0, 0);
    when(receiptParser.parse(any(), any())).thenReturn(result);
    ParsedReceipt parsed = new ParsedReceipt(null, null, List.of());
    when(decoder.decode("good toon")).thenReturn(Optional.of(parsed));
    stubSeed(parsed);

    analyser().run(ID, false);

    verify(toonRepair, never()).repair(any());
    verify(analysisService).applyProcessed(eq(ID), eq(result), any(), any());
  }

  /** The batch poller lands its members through {@code applyParsed}, so it repairs too. */
  @Test
  void applyParsedRepairsBatchMemberResponse() {
    when(decoder.decode("bad,toon")).thenReturn(Optional.empty());
    when(toonRepair.repair("bad,toon"))
        .thenReturn(new ToonRepair.Result("good toon", List.of("quoted"), List.of()));
    ParsedReceipt parsed = new ParsedReceipt(null, null, List.of());
    when(decoder.decode("good toon")).thenReturn(Optional.of(parsed));
    SeededReceipt seeded = stubSeed(parsed);

    assertThat(
            analyser()
                .applyParsed(
                    ID, new ReceiptParseResult("bad,toon", 1, 1, 0, 0), BigDecimal.ONE, "reason"))
        .isTrue();

    verify(analysisService)
        .applyProcessed(
            eq(ID),
            eq(new ReceiptParseResult("good toon", "bad,toon", 1, 1, 0, 0)),
            eq(BigDecimal.ONE),
            eq(seeded));
  }

  @Test
  void reparseKeepsTheEarliestOriginalAcrossSeveralEdits() {
    Receipt edited =
        withOriginal(parsedReceipt(ReceiptState.PROCESSED, "first edit"), "the model said this");
    when(receiptService.findById(ID)).thenReturn(Optional.of(edited));
    ParsedReceipt parsed = new ParsedReceipt(null, null, List.of());
    when(decoder.decode("second edit")).thenReturn(Optional.of(parsed));
    stubSeed(parsed);

    analyser().reparse(ID, "second edit");

    verify(analysisService)
        .applyProcessed(
            eq(ID),
            eq(new ReceiptParseResult("second edit", "the model said this", 10, 5, 0, 0)),
            any(),
            any());
  }

  @Test
  void reparseBackToTheOriginalClearsTheEditedMark() {
    Receipt edited =
        withOriginal(parsedReceipt(ReceiptState.PROCESSED, "an edit"), "the model said this");
    when(receiptService.findById(ID)).thenReturn(Optional.of(edited));
    ParsedReceipt parsed = new ParsedReceipt(null, null, List.of());
    when(decoder.decode("the model said this")).thenReturn(Optional.of(parsed));
    stubSeed(parsed);

    analyser().reparse(ID, "the model said this");

    verify(analysisService)
        .applyProcessed(
            eq(ID), eq(new ReceiptParseResult("the model said this", 10, 5, 0, 0)), any(), any());
  }

  /** Browsers post a textarea with CRLF line endings; that alone is not an edit. */
  @Test
  void reparseTreatsCrlfLineEndingsFromTheBrowserAsNoEdit() {
    Receipt edited =
        withOriginal(parsedReceipt(ReceiptState.PROCESSED, "an edit"), "line one\nline two");
    when(receiptService.findById(ID)).thenReturn(Optional.of(edited));
    ParsedReceipt parsed = new ParsedReceipt(null, null, List.of());
    when(decoder.decode("line one\nline two")).thenReturn(Optional.of(parsed));
    stubSeed(parsed);

    analyser().reparse(ID, "line one\r\nline two");

    verify(analysisService)
        .applyProcessed(
            eq(ID), eq(new ReceiptParseResult("line one\nline two", 10, 5, 0, 0)), any(), any());
  }

  @Test
  void reparseThatFailsKeepsTheOriginalToo() {
    when(receiptService.findById(ID))
        .thenReturn(Optional.of(parsedReceipt(ReceiptState.FAILED, "bad,toon")));
    when(decoder.decode("still bad")).thenReturn(Optional.empty());

    analyser().reparse(ID, "still bad");

    verify(analysisService)
        .failUndecodable(
            eq(ID), any(), eq(new ReceiptParseResult("still bad", "bad,toon", 10, 5, 0, 0)), any());
    verify(toonRepair, never()).repair(any()); // the operator's own text is never rewritten
  }

  private static Receipt withOriginal(Receipt receipt, String original) {
    return new Receipt(
        receipt.receiptId(),
        receipt.state(),
        receipt.capturedAt(),
        receipt.source(),
        receipt.originalPath(),
        receipt.editedPath(),
        receipt.editRecipe(),
        receipt.aiNote(),
        receipt.batchId(),
        receipt.warmupBatchId(),
        receipt.parseRaw(),
        original,
        receipt.merchantText(),
        receipt.receiptDate(),
        receipt.totalAmount(),
        receipt.currencyCode(),
        receipt.accountId(),
        receipt.transactionId(),
        receipt.deletedAt(),
        receipt.parseError(),
        receipt.tokensIn(),
        receipt.tokensOut(),
        receipt.tokensCacheWrite(),
        receipt.tokensCacheRead(),
        receipt.parseCost(),
        receipt.merchantCity(),
        receipt.merchantCountry(),
        receipt.receiptTime(),
        receipt.receiptNumber(),
        receipt.payeeId(),
        receipt.note(),
        receipt.fundingTotal(),
        receipt.baseTotal());
  }
}
