package volkovandr.hauptbuch.statements;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.PayingAccountDetector;
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * The PDF way into a statement (statements.md §3.2, slice e1): extract the text locally, propose
 * the account from the unmasked text, mask the operator's own identifiers, and keep the result as
 * the statement's editable text. The AI call that turns the text into lines is slice e2; until
 * then a PDF statement stays {@code new}, with no lines.
 */
@Service
public class StatementPdfService {

  private static final Logger LOG = LoggerFactory.getLogger(StatementPdfService.class);

  private final StatementRepository statementRepository;
  private final StatementStorage storage;
  private final StatementPdfText pdfText;
  private final StatementService statementService;
  private final PayingAccountDetector accountDetector;

  StatementPdfService(
      StatementRepository statementRepository,
      StatementStorage storage,
      StatementPdfText pdfText,
      StatementService statementService,
      PayingAccountDetector accountDetector) {
    this.statementRepository = statementRepository;
    this.storage = storage;
    this.pdfText = pdfText;
    this.statementService = statementService;
    this.accountDetector = accountDetector;
  }

  /**
   * Read a staged PDF without saving anything: that it has a text layer, and the account proposed
   * from the unmasked text.
   *
   * @throws StatementFormatException when the file is not a PDF or has no text layer
   */
  public PdfUploadPreview preview(String filePath) {
    String text = pdfText.extract(storage.read(filePath));
    return new PdfUploadPreview(text.length(), accountDetector.detectInText(text));
  }

  /**
   * Create the {@code new} statement for a staged PDF: the extracted text, pre-masked of the
   * operator's own identifiers and any IBAN/BIC-shaped string, ready to edit.
   *
   * @return the new statement's id
   * @throws StatementFormatException when the file is unreadable or the account cannot hold a
   *     statement
   */
  @Transactional
  public long create(
      long statementProfileId, String filePath, String originalFilename, long accountId) {
    statementService.statementAccount(accountId);
    String text = pdfText.extract(storage.read(filePath));
    String masked = StatementTextMasker.mask(text, accountDetector.ownIdentifiers());
    long statementId =
        statementRepository.insertPdf(
            statementProfileId, accountId, originalFilename, filePath, masked);
    LOG.info("Created PDF statement {} on account {}", statementId, accountId);
    return statementId;
  }

  /** The statement's text as it stands, or null for a statement that did not come from a PDF. */
  public String sentText(long statementId) {
    return statementRepository.findSentText(statementId).orElse(null);
  }

  /**
   * Replace the text that will be sent for parsing — the operator's edit of the masked extraction.
   *
   * @throws StatementFormatException when the statement is not a PDF statement awaiting a parse
   */
  public void updateText(long statementId, String text) {
    statementService.get(statementId);
    if (statementRepository.updateSentText(statementId, text) == 0) {
      throw new StatementFormatException("This statement's text can no longer be edited.");
    }
  }
}
