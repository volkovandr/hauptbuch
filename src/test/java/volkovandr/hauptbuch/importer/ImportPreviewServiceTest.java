package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6, import.md §12): {@link ImportPreviewService} composes the pure parser
 * pieces (a2–a4) into the upload preview and applies the owner's charset / date-order override — no
 * Spring, no DB.
 */
class ImportPreviewServiceTest {

  private static final String DAY_MONTH_BANK =
      """
      !Type:Bank
      D01/07'2004
      T-12.34
      PGrocer
      LFood
      ^
      D28/07'2004
      T-5.00
      PBaker
      LFood
      ^
      """;

  private static final String AMBIGUOUS_CARD =
      """
      !Type:CCard
      D01/02'2005
      T-9.99
      PShop
      LStuff
      ^
      """;

  /** Opens with Money's opening-balance self-transfer, which names the account (§5.1). */
  private static final String BANK_WITH_OPENING_BALANCE =
      """
      !Type:Bank
      D25/12'2013
      T0.00
      CX
      POpening Balance
      L[Bank24ru-EUR]
      ^
      D28/07'2014
      T-5.00
      PBaker
      LFood
      ^
      """;

  private static final String INVESTMENT =
      """
      !Type:Invst
      D01/07'2004
      ^
      """;

  private final ImportPreviewService service = new ImportPreviewService(new QifParser());

  private static PendingImportUpload rawUpload(String text) {
    return PendingImportUpload.of("tok", "export.qif", text.getBytes(StandardCharsets.UTF_8));
  }

  /** An upload with the account already stated, for assertions not about account-name deduction. */
  private static PendingImportUpload upload(String text) {
    return rawUpload(text).withChoice(null, null, "Current Account");
  }

  @Test
  void previewsParsedFileWithTypeCountAndDateEvidence() {
    ImportPreview preview = service.preview(upload(DAY_MONTH_BANK));

    assertThat(preview.rejected()).isFalse();
    assertThat(preview.proposedAccountType()).isEqualTo("asset");
    assertThat(preview.recordCount()).isEqualTo(2);
    assertThat(preview.charset()).isEqualTo("utf_8");
    assertThat(preview.detectedCharset()).isEqualTo("utf_8");
    assertThat(preview.dateOrder()).isEqualTo("day_month");
    assertThat(preview.dateEvidence()).contains("D28/07'2004");
    assertThat(preview.previewText()).contains("!Type:Bank");
  }

  @Test
  void reportsAnAmbiguousDateFileLoudly() {
    ImportPreview preview = service.preview(upload(AMBIGUOUS_CARD));

    assertThat(preview.proposedAccountType()).isEqualTo("liability");
    assertThat(preview.dateOrder()).isEqualTo("ambiguous");
    assertThat(preview.detectedDateOrder()).isEqualTo("ambiguous");
  }

  @Test
  void dateOrderOverrideWinsOverDetectionButKeepsTheDetectedValueVisible() {
    ImportPreview preview =
        service.preview(upload(DAY_MONTH_BANK).withChoice(null, "month_day", null));

    assertThat(preview.dateOrder()).isEqualTo("month_day");
    assertThat(preview.detectedDateOrder()).isEqualTo("day_month");
  }

  @Test
  void charsetOverrideRedecodesTheFile() {
    ImportPreview preview =
        service.preview(upload(DAY_MONTH_BANK).withChoice("windows_1252", null, null));

    assertThat(preview.charset()).isEqualTo("windows_1252");
    assertThat(preview.detectedCharset()).isEqualTo("utf_8");
  }

  @Test
  void surfacesTheRejectionForAnInvestmentFile() {
    ImportPreview preview = service.preview(upload(INVESTMENT));

    assertThat(preview.rejected()).isTrue();
    assertThat(preview.rejection()).contains("investment");
    assertThat(preview.recordCount()).isNull();
    assertThat(preview.previewText()).contains("!Type:Invst");
  }

  @Test
  void deducesTheAccountNameFromTheOpeningBalanceRecord() {
    assertThat(service.deduceAccountName(rawUpload(BANK_WITH_OPENING_BALANCE)))
        .contains("Bank24ru-EUR");
  }

  @Test
  void hasNoAccountNameToDeduceWhenThereIsNoOpeningBalanceRecord() {
    assertThat(service.deduceAccountName(rawUpload(DAY_MONTH_BANK))).isEmpty();
  }

  @Test
  void previewAwaitsTheAccountNameUntilItIsDeducedOrStated() {
    ImportPreview awaiting = service.preview(rawUpload(DAY_MONTH_BANK));
    assertThat(awaiting.awaitingAccountName()).isTrue();
    assertThat(awaiting.accountName()).isNull();
    // the rest of the preview is still built — the account name only gates staging
    assertThat(awaiting.recordCount()).isEqualTo(2);

    ImportPreview stated =
        service.preview(rawUpload(DAY_MONTH_BANK).withChoice(null, null, "Current Account"));
    assertThat(stated.awaitingAccountName()).isFalse();
    assertThat(stated.accountName()).isEqualTo("Current Account");
    assertThat(stated.accountNameDeduced()).isFalse();
  }

  @Test
  void previewMarksAccountNameDeducedFromTheFile() {
    PendingImportUpload deduced =
        service
            .deduceAccountName(rawUpload(BANK_WITH_OPENING_BALANCE))
            .map(rawUpload(BANK_WITH_OPENING_BALANCE)::withDeducedAccountName)
            .orElseThrow();

    ImportPreview preview = service.preview(deduced);

    assertThat(preview.accountName()).isEqualTo("Bank24ru-EUR");
    assertThat(preview.accountNameDeduced()).isTrue();
  }
}
