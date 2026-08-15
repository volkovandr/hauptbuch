package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.LedgerService;

/**
 * Integration tier (§1.5): the mobile capture surface driven through its controller against real
 * Postgres and a temp storage root — capture upload (incl. magic-byte rejection), the grid, image
 * serving, and mobile instant-delete. The stage's mobile acceptance surface (§9b).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptCaptureScreenIntegrationTest {

  private static final Path STORAGE_ROOT = tempRoot();
  private static final String CAPTURE_PATH = "/receipts/capture";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired LedgerService ledgerService;

  @DynamicPropertySource
  static void storageRoot(DynamicPropertyRegistry registry) {
    registry.add("hauptbuch.receipts.storage-root", STORAGE_ROOT::toString);
  }

  @Test
  void capturePageOffersCameraAndGalleryAffordances() throws Exception {
    mockMvc
        .perform(get(CAPTURE_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Take a photo")))
        .andExpect(content().string(containsString("Choose from gallery")))
        // Camera-first: only the camera input forces the rear camera.
        .andExpect(content().string(containsString("capture=\"environment\"")));
  }

  @Test
  void uploadStoresTheScanAndRedirectsToTheGrid() throws Exception {
    mockMvc
        .perform(multipart("/receipts").file(ReceiptImages.jpegPart()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(CAPTURE_PATH));

    String path = latestOriginalPath();
    assertThat(path).startsWith("originals/");
    assertThat(Files.exists(STORAGE_ROOT.resolve(path))).isTrue();
  }

  @Test
  void uploadRejectsNonImageBytesWithClearMessage() throws Exception {
    // A lying content type (image/jpeg) over PDF bytes: validation is by magic bytes, not the
    // client's declared type, so this is still rejected (§9b).
    MockMultipartFile bogus =
        new MockMultipartFile(
            "image", "photo.jpg", "image/jpeg", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/receipts").file(bogus))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("JPEG and PNG")));
  }

  @Test
  void gridShowsCapturedReceiptsAndServesTheirImages() throws Exception {
    mockMvc
        .perform(multipart("/receipts").file(ReceiptImages.jpegPart()))
        .andExpect(status().is3xxRedirection());
    long id = latestReceiptId();

    // The grid renders a tile linking to the full-scale image.
    mockMvc
        .perform(get(CAPTURE_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/receipts/" + id + "/image")));

    // Thumbnail and original both serve as images.
    mockMvc
        .perform(get("/receipts/" + id + "/thumb"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"));
    mockMvc
        .perform(get("/receipts/" + id + "/image"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/jpeg"));
  }

  @Test
  void mobileDeleteRemovesNewReceiptAndItsFiles() throws Exception {
    mockMvc
        .perform(multipart("/receipts").file(ReceiptImages.jpegPart()))
        .andExpect(status().is3xxRedirection());
    long id = latestReceiptId();
    String path = latestOriginalPath();
    assertThat(Files.exists(STORAGE_ROOT.resolve(path))).isTrue();

    mockMvc
        .perform(post("/receipts/capture/" + id + "/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(CAPTURE_PATH));

    assertThat(Files.exists(STORAGE_ROOT.resolve(path))).isFalse();
    assertThat(isSoftDeleted(id)).isTrue();
  }

  @Test
  void servingAnUnknownReceiptIs404() throws Exception {
    mockMvc.perform(get("/receipts/999999/thumb")).andExpect(status().isNotFound());
  }

  /**
   * The mobile grid's state dot gets the same grey "Void" treatment as the PC badge for a committed
   * receipt whose transaction was voided from the register (issue tracker #08).
   */
  @Test
  void gridShowsGreyVoidDotForCommittedReceiptWhoseTransactionWasVoided() throws Exception {
    mockMvc
        .perform(multipart("/receipts").file(ReceiptImages.jpegPart()))
        .andExpect(status().is3xxRedirection());
    long id = latestReceiptId();
    jdbcClient
        .sql("update receipt set state = 'committed' where receipt_id = :id")
        .param("id", id)
        .update();
    long transactionId =
        jdbcClient
            .sql("insert into transaction (date) values (current_date) returning transaction_id")
            .query(Long.class)
            .single();
    jdbcClient
        .sql("update receipt set transaction_id = :tid where receipt_id = :id")
        .param("tid", transactionId)
        .param("id", id)
        .update();
    ledgerService.voidTransaction(transactionId);

    mockMvc
        .perform(get(CAPTURE_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("state-dot--void")))
        .andExpect(content().string(containsString("title=\"Void\"")))
        .andExpect(content().string(not(containsString("state-dot--committed"))));
  }

  private long latestReceiptId() {
    return jdbcClient
        .sql("select receipt_id from receipt order by receipt_id desc limit 1")
        .query(Long.class)
        .single();
  }

  private String latestOriginalPath() {
    return jdbcClient
        .sql("select original_path from receipt order by receipt_id desc limit 1")
        .query(String.class)
        .single();
  }

  private boolean isSoftDeleted(long id) {
    return jdbcClient
        .sql("select deleted_at is not null from receipt where receipt_id = :id")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("hauptbuch-capture-test");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
