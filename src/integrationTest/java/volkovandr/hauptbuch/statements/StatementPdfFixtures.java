package volkovandr.hauptbuch.statements;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Shared set-up for the PDF statement screen tests: a generated PDF and its upload. */
final class StatementPdfFixtures {

  private StatementPdfFixtures() {}

  /** A one-page PDF with one text line per argument; no arguments gives a page with no text. */
  static byte[] pdf(String... lines) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      if (lines.length > 0) {
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
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }

  /** Upload the bytes as a PDF statement under the profile; return the confirm-step URL. */
  static String upload(MockMvc mockMvc, long profileId, byte[] bytes) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                multipart("/statements/upload")
                    .file(new MockMultipartFile("file", "2026-05.pdf", "application/pdf", bytes))
                    .param("profile", String.valueOf(profileId)))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("/statements/confirm?*"))
            .andReturn();
    return Objects.requireNonNull(result.getResponse().getRedirectedUrl());
  }
}
