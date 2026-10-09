package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.PayingAccountDetector;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * Unit tier (CLAUDE.md §6): the orchestration of a PDF upload with the repository, the extractor
 * and the detector mocked — the account is proposed from the <em>unmasked</em> text, the stored
 * text is the masked one, a refused PDF stores nothing, and the text is editable only before a
 * parse.
 */
@ExtendWith(MockitoExtension.class)
class StatementPdfServiceTest {

  private static final long PROFILE_ID = 4L;
  private static final long ACCOUNT_ID = 11L;
  private static final long STATEMENT_ID = 21L;
  private static final String PATH = "2026/05/x.pdf";
  private static final byte[] BYTES = {1, 2, 3};
  private static final String RAW = "Konto XX00 1111 2222\n02.05.2026 ShopAaa -12,50";

  @Mock private StatementRepository statementRepository;
  @Mock private StatementStorage storage;
  @Mock private StatementPdfText pdfText;
  @Mock private StatementService statementService;
  @Mock private PayingAccountDetector detector;

  private StatementPdfService service;

  @BeforeEach
  void setUp() {
    service =
        new StatementPdfService(statementRepository, storage, pdfText, statementService, detector);
  }

  @Test
  void previewProposesTheAccountFromTheUnmaskedText() {
    when(storage.read(PATH)).thenReturn(BYTES);
    when(pdfText.extract(BYTES)).thenReturn(RAW);
    when(detector.detectInText(RAW)).thenReturn(OptionalLong.of(ACCOUNT_ID));

    PdfUploadPreview preview = service.preview(PATH);

    assertThat(preview.characters()).isEqualTo(RAW.length());
    assertThat(preview.proposedAccountId()).hasValue(ACCOUNT_ID);
  }

  @Test
  void createStoresTheMaskedTextAsNewStatement() {
    when(storage.read(PATH)).thenReturn(BYTES);
    when(pdfText.extract(BYTES)).thenReturn(RAW);
    when(detector.ownIdentifiers()).thenReturn(List.of("XX00 1111 2222"));
    when(statementRepository.insertPdf(
            PROFILE_ID, ACCOUNT_ID, "may.pdf", PATH, "Konto [ACCOUNT]\n02.05.2026 ShopAaa -12,50"))
        .thenReturn(STATEMENT_ID);

    assertThat(service.create(PROFILE_ID, PATH, "may.pdf", ACCOUNT_ID)).isEqualTo(STATEMENT_ID);

    verify(statementService).statementAccount(ACCOUNT_ID);
  }

  @Test
  void pdfWithoutTextLayerStoresNothing() {
    when(storage.read(PATH)).thenReturn(BYTES);
    when(pdfText.extract(BYTES)).thenThrow(new StatementFormatException("no text layer"));

    assertThatThrownBy(() -> service.create(PROFILE_ID, PATH, "may.pdf", ACCOUNT_ID))
        .isInstanceOf(StatementFormatException.class);

    verifyNoInteractions(statementRepository);
  }

  @Test
  void accountThatCannotHoldStatementIsRefusedBeforeAnyReading() {
    when(statementService.statementAccount(ACCOUNT_ID))
        .thenThrow(new StatementFormatException("Choose the account this statement is for."));

    assertThatThrownBy(() -> service.create(PROFILE_ID, PATH, "may.pdf", ACCOUNT_ID))
        .isInstanceOf(StatementFormatException.class);

    verifyNoInteractions(storage, pdfText);
  }

  @Test
  void sentTextIsNullForStatementThatIsNotPdf() {
    when(statementRepository.findSentText(STATEMENT_ID)).thenReturn(Optional.empty());

    assertThat(service.sentText(STATEMENT_ID)).isNull();
  }

  @Test
  void updateTextOverwritesTheStoredText() {
    when(statementRepository.updateSentText(STATEMENT_ID, "edited")).thenReturn(1);

    service.updateText(STATEMENT_ID, "edited");

    verify(statementService).get(STATEMENT_ID);
  }

  @Test
  void updateTextIsRefusedOnceTheStatementCannotTakeIt() {
    when(statementRepository.updateSentText(STATEMENT_ID, "edited")).thenReturn(0);

    assertThatThrownBy(() -> service.updateText(STATEMENT_ID, "edited"))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("can no longer be edited");
  }
}
