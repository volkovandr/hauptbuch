package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (CLAUDE.md §6): PDFBox extraction of a statement's text layer, and the two refusals — a
 * PDF without a text layer and bytes that are not a PDF.
 */
class StatementPdfTextTest {

  private final StatementPdfText extractor = new StatementPdfText();

  private static byte[] pdf(String... lines) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.setLeading(14);
        content.newLineAtOffset(50, 700);
        for (String line : lines) {
          content.showText(line);
          content.newLine();
        }
        content.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void textLayerIsExtractedLineByLine() throws IOException {
    String text = extractor.extract(pdf("Statement May 2026", "02.05.2026 ShopAaa -12.50"));

    assertThat(text).contains("Statement May 2026").contains("02.05.2026 ShopAaa -12.50");
    assertThat(text.indexOf("Statement")).isLessThan(text.indexOf("ShopAaa"));
  }

  @Test
  void pdfWithoutTextLayerIsRefused() throws IOException {
    byte[] blank;
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      blank = out.toByteArray();
    }

    assertThatThrownBy(() -> extractor.extract(blank))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("no text layer");
  }

  @Test
  void bytesThatAreNotPdfAreRefused() {
    assertThatThrownBy(() -> extractor.extract("a,b,c\n".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("not a PDF");
  }
}
