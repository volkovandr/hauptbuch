package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import volkovandr.hauptbuch.ledger.AiSettings;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.statements.repository.ParseUsage;
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * Unit tier: the orchestration of one statement parse with every collaborator mocked — what is sent
 * (the edited text and the profile's note, nothing else), how usage and cost are frozen, and how
 * each outcome lands: processed, undecodable (raw kept), transport failure, unexpected error.
 */
@ExtendWith(MockitoExtension.class)
class StatementParseServiceTest {

  private static final long STATEMENT_ID = 21L;
  private static final long PROFILE_ID = 4L;
  private static final String TEXT = "02.05.2026 ShopAaa -12,50";
  private static final String RAW = "statement:\n  periodStart: 2026-05-01\n";
  private static final AiSettings CONFIG =
      new AiSettings(
          "model-x",
          "key-1",
          new BigDecimal("3"),
          new BigDecimal("15"),
          new BigDecimal("3.75"),
          new BigDecimal("0.30"));

  @Mock private StatementRepository statementRepository;
  @Mock private StatementProfileService profileService;
  @Mock private StatementService statementService;
  @Mock private StatementParser parser;
  @Mock private ToonStatementDecoder decoder;
  @Mock private StatementParseResults results;
  @Mock private SettingsService settingsService;
  @Mock private StatementMatchRepository matchRepository;

  private StatementParseService service;

  @BeforeEach
  void setUp() {
    service =
        new StatementParseService(
            statementRepository,
            profileService,
            statementService,
            parser,
            new StatementPromptBuilder(),
            decoder,
            results,
            settingsService,
            matchRepository);
  }

  private void statementAwaitingParse() {
    when(statementService.get(STATEMENT_ID)).thenReturn(statement());
    when(statementRepository.claimForParse(STATEMENT_ID)).thenReturn(true);
  }

  private void callPrerequisites() {
    when(settingsService.aiConfig()).thenReturn(CONFIG);
    when(settingsService.statementSystemPrompt()).thenReturn(null);
    when(statementRepository.findSentText(STATEMENT_ID)).thenReturn(Optional.of(TEXT));
    when(profileService.getIncludingDeleted(PROFILE_ID))
        .thenReturn(profileWithNote("Rate in text"));
  }

  @Test
  void sendsTheEditedTextWithTheNoteAndStoresTheDecodedParse() {
    statementAwaitingParse();
    callPrerequisites();
    when(parser.parse(any())).thenReturn(new StatementParseResult(RAW, 1000, 200, 0, 500));
    ParsedStatement parsed = new ParsedStatement(null, null, null, null, List.of());
    when(decoder.decode(RAW)).thenReturn(Optional.of(parsed));

    boolean processed = service.parse(STATEMENT_ID);

    assertThat(processed).isTrue();
    ArgumentCaptor<StatementParseRequest> request =
        ArgumentCaptor.forClass(StatementParseRequest.class);
    verify(parser).parse(request.capture());
    assertThat(request.getValue().model()).isEqualTo("model-x");
    assertThat(request.getValue().apiKey()).isEqualTo("key-1");
    assertThat(request.getValue().userText()).contains("Rate in text").endsWith(TEXT);
    ArgumentCaptor<ParseUsage> usage = ArgumentCaptor.forClass(ParseUsage.class);
    verify(results).applyProcessed(eq(STATEMENT_ID), eq(parsed), usage.capture(), eq(RAW));
    assertThat(usage.getValue().tokensIn()).isEqualTo(1000);
    assertThat(usage.getValue().tokensCacheRead()).isEqualTo(500);
    // (1000*3 + 200*15 + 500*0.30) / 1e6
    assertThat(usage.getValue().cost()).isEqualByComparingTo("0.006150");
  }

  @Test
  void anUndecodableBodyFailsKeepingTheRawResponseAndUsage() {
    statementAwaitingParse();
    callPrerequisites();
    when(parser.parse(any())).thenReturn(new StatementParseResult(RAW, 10, 5, 0, 0));
    when(decoder.decode(RAW)).thenReturn(Optional.empty());

    assertThat(service.parse(STATEMENT_ID)).isFalse();

    verify(results)
        .failUndecodable(
            eq(STATEMENT_ID), eq("Could not decode the parser response"), any(), eq(RAW));
    verify(results, never()).applyProcessed(anyLong(), any(), any(), any());
  }

  @Test
  void transportFailureFailsWithItsMessageAndNoUsage() {
    statementAwaitingParse();
    callPrerequisites();
    when(parser.parse(any())).thenThrow(new StatementParseException("401 unauthorized"));

    assertThat(service.parse(STATEMENT_ID)).isFalse();

    verify(results).failTransport(STATEMENT_ID, "401 unauthorized");
  }

  @Test
  void blankTextFailsWithoutCallingTheParser() {
    statementAwaitingParse();
    when(settingsService.aiConfig()).thenReturn(CONFIG);
    when(statementRepository.findSentText(STATEMENT_ID)).thenReturn(Optional.of("  "));

    assertThat(service.parse(STATEMENT_ID)).isFalse();

    verify(results).failTransport(STATEMENT_ID, "The statement has no text to parse");
    verifyNoInteractions(parser);
  }

  @Test
  void anUnexpectedErrorStillLandsFailedNotStuckProcessing() {
    statementAwaitingParse();
    callPrerequisites();
    when(parser.parse(any())).thenThrow(new IllegalStateException("boom"));

    assertThat(service.parse(STATEMENT_ID)).isFalse();

    verify(results).failTransport(STATEMENT_ID, "Unexpected error: boom");
  }

  @Test
  void unclaimableStatementIsRefusedWithoutCall() {
    when(statementService.get(STATEMENT_ID)).thenReturn(statement());
    when(statementRepository.claimForParse(STATEMENT_ID)).thenReturn(false);

    assertThatThrownBy(() -> service.parse(STATEMENT_ID))
        .isInstanceOf(StatementFormatException.class);

    verifyNoInteractions(parser, results);
  }

  @Test
  void theStartupSweepFailsOrphanedProcessingStatements() {
    when(statementRepository.failOrphanedProcessing(any())).thenReturn(2);

    service.sweepOrphansOnStartup();

    verify(statementRepository).failOrphanedProcessing("Interrupted by a restart while processing");
  }

  private static Statement statement() {
    return new Statement(
        STATEMENT_ID,
        PROFILE_ID,
        11L,
        "processing",
        "may.pdf",
        "p",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static StatementProfile profileWithNote(String note) {
    return new StatementProfile(
        PROFILE_ID,
        "BankBbb PDF",
        "pdf",
        10,
        3,
        note,
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

  @Test
  void reseedReplacesTheLinesFromTheEditedTextWithoutCallingTheParser() {
    when(statementService.get(STATEMENT_ID)).thenReturn(statement());
    when(matchRepository.findMatches(STATEMENT_ID)).thenReturn(List.of());
    ParsedStatement parsed = new ParsedStatement(null, null, null, null, List.of());
    when(decoder.decode(RAW)).thenReturn(Optional.of(parsed));

    service.reseed(STATEMENT_ID, RAW);

    verify(results).applyReseed(STATEMENT_ID, parsed, RAW);
    verifyNoInteractions(parser);
  }

  @Test
  void reseedIsRefusedWhileAnyLineIsMatched() {
    when(statementService.get(STATEMENT_ID)).thenReturn(statement());
    when(matchRepository.findMatches(STATEMENT_ID))
        .thenReturn(
            List.of(new StatementMatch(1L, 2L, 3L, null, null, BigDecimal.ONE, "reconciled")));

    assertThatThrownBy(() -> service.reseed(STATEMENT_ID, RAW))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("Unmatch");
    verify(results, never()).applyReseed(anyLong(), any(), any());
  }

  @Test
  void reseedWithUndecodableTextChangesNothing() {
    when(statementService.get(STATEMENT_ID)).thenReturn(statement());
    when(matchRepository.findMatches(STATEMENT_ID)).thenReturn(List.of());
    when(decoder.decode("garbage")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.reseed(STATEMENT_ID, "garbage"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("decode");
    verify(results, never()).applyReseed(anyLong(), any(), any());
  }
}
