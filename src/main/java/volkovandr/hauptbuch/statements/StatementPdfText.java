package volkovandr.hauptbuch.statements;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Local text extraction for a statement PDF (statements.md §3.2): PDFBox reads the text layer a
 * bank-generated statement carries. The PDF itself never leaves the machine, and a scan without a
 * text layer is refused — v1 has no image path.
 */
@Component
class StatementPdfText {

  /**
   * The text of every page, in reading order.
   *
   * @throws StatementFormatException when the bytes are not a readable PDF, or it has no text layer
   */
  String extract(byte[] pdf) {
    String text;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      text = stripper.getText(document);
    } catch (IOException e) {
      throw new StatementFormatException("That file is not a PDF Hauptbuch can read.");
    }
    if (text.isBlank()) {
      throw new StatementFormatException(
          "That PDF has no text layer — it looks like a scan, which is not supported.");
    }
    return text.strip();
  }
}
