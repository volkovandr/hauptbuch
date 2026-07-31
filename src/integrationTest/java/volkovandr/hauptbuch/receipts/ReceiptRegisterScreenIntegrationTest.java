package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
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

/**
 * Integration tier (§1.5): the PC receipt register and its actions driven through the controller
 * against real Postgres and a temp storage root — rendering, the state filter, the context menu and
 * keep/delete-files dialog fragments, the delete ladder, committed-skip, and the mobile root
 * redirect. The 9c menu always offers the 3-way delete dialog (no instant rung, no Discard).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptRegisterScreenIntegrationTest {

  private static final Path STORAGE_ROOT = tempRoot();
  private static final String RECEIPTS_PATH = "/receipts";
  private static final String DELETE_PATH = "/receipts/delete";
  private static final String ID = "id";
  private static final String REMOVE_FILES = "removeFiles";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;

  @DynamicPropertySource
  static void storageRoot(DynamicPropertyRegistry registry) {
    registry.add("hauptbuch.receipts.storage-root", STORAGE_ROOT::toString);
  }

  @Test
  void registerRendersTheFullColumnSet() throws Exception {
    upload();

    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Captured")))
        .andExpect(content().string(containsString("Merchant")))
        .andExpect(content().string(containsString("Total")))
        .andExpect(content().string(containsString("Work queue")));
  }

  @Test
  void stateFilterHidesCommittedByDefaultButEverythingShowsIt() throws Exception {
    long committed = upload();
    setState(committed, "committed");

    // Default = work queue: no committed row badge (the filter dropdown's "Committed" option is
    // always present, so assert on the row badge class, not the word).
    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(content().string(not(containsString("rstate--committed"))));

    // Everything: the committed row (and its badge) shows.
    mockMvc
        .perform(get(RECEIPTS_PATH).param("state", "all"))
        .andExpect(content().string(containsString("rstate--committed")));
  }

  @Test
  void contextMenuOffersTheDeleteDialogForNewSelection() throws Exception {
    long id = upload();

    mockMvc
        .perform(get("/receipts/menu").param(ID, String.valueOf(id)))
        .andExpect(status().isOk())
        // A single Delete… action that opens the 3-way dialog — `new` included (2026-07-31).
        // The count reads as an amount, not IDs: singular "receipt".
        .andExpect(content().string(containsString("Delete 1 receipt")))
        .andExpect(content().string(containsString("/receipts/delete-dialog")))
        // A single selection also offers "View image" → the full-size scan.
        .andExpect(content().string(containsString("View image")))
        .andExpect(content().string(containsString("/receipts/" + id + "/image")))
        // The `discarded` state is retired: no Discard action.
        .andExpect(content().string(not(containsString("Discard"))));
  }

  @Test
  void contextMenuPluralisesTheCounts() throws Exception {
    long one = upload();
    long two = upload();

    mockMvc
        .perform(
            get("/receipts/menu").param(ID, String.valueOf(one)).param(ID, String.valueOf(two)))
        .andExpect(content().string(containsString("Delete 2 receipts")));
  }

  @Test
  void registerRowShowsThumbnailAndOpensTheProcessingScreen() throws Exception {
    long id = upload();

    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(status().isOk())
        // The thumbnail is a plain preview (no full-image link — that moved to the menu, so it no
        // longer competes with the row's double-click).
        .andExpect(content().string(containsString("/receipts/" + id + "/thumb")))
        // Double-click opens the processing screen; the row carries its URL for the keyboard leaf.
        .andExpect(content().string(containsString("data-receipt-open=\"/receipts/" + id + "\"")));
  }

  @Test
  void pcUploadStoresReceiptTaggedPcAndRedirects() throws Exception {
    mockMvc
        .perform(multipart("/receipts/upload").file(jpegPart()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(RECEIPTS_PATH));

    String source =
        jdbcClient
            .sql("select source from receipt order by receipt_id desc limit 1")
            .query(String.class)
            .single();
    assertThat(source).isEqualTo("pc");
  }

  @Test
  void deleteFilesTooRemovesTheRowAndItsFiles() throws Exception {
    long id = upload();
    String path = originalPath(id);
    assertThat(Files.exists(STORAGE_ROOT.resolve(path))).isTrue();

    // The dialog's "Delete files too" choice: soft-delete the row and remove the files.
    mockMvc
        .perform(post(DELETE_PATH).param(ID, String.valueOf(id)).param(REMOVE_FILES, "true"))
        .andExpect(status().isOk());

    assertThat(Files.exists(STORAGE_ROOT.resolve(path))).isFalse();
    assertThat(isLive(id)).isFalse();
  }

  @Test
  void keepFilesDialogDeletesTheRowButLeavesFilesOnDisk() throws Exception {
    long id = upload();
    setState(id, "pre_processed");
    String path = originalPath(id);

    // The middle rung offers the three-way file choice.
    mockMvc
        .perform(get("/receipts/delete-dialog").param(ID, String.valueOf(id)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Keep files")))
        .andExpect(content().string(containsString("Delete files too")));

    mockMvc
        .perform(post(DELETE_PATH).param(ID, String.valueOf(id)).param(REMOVE_FILES, "false"))
        .andExpect(status().isOk());

    assertThat(isLive(id)).isFalse();
    // Files kept per the choice.
    assertThat(Files.exists(STORAGE_ROOT.resolve(path))).isTrue();
  }

  @Test
  void deleteSkipsCommittedMembersOfSelection() throws Exception {
    long fresh = upload();
    long committed = upload();
    setState(committed, "committed");

    mockMvc
        .perform(
            post(DELETE_PATH)
                .param(ID, String.valueOf(fresh))
                .param(ID, String.valueOf(committed))
                .param(REMOVE_FILES, "true"))
        .andExpect(status().isOk());

    // The new one is deleted; the committed one is skipped (its dialog is 9g's concern).
    assertThat(isLive(fresh)).isFalse();
    assertThat(isLive(committed)).isTrue();
  }

  @Test
  void menuReportsCommittedMembersAsSkipped() throws Exception {
    long fresh = upload();
    long committed = upload();
    setState(committed, "committed");

    mockMvc
        .perform(
            get("/receipts/menu")
                .param(ID, String.valueOf(fresh))
                .param(ID, String.valueOf(committed)))
        .andExpect(content().string(containsString("1 of 2 selected were committed")));
  }

  @Test
  void phoneUserAgentRedirectsRootToCapture() throws Exception {
    mockMvc
        .perform(get("/").header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobi) Chrome"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/receipts/capture"));
  }

  @Test
  void desktopEscapeHatchSkipsTheRedirect() throws Exception {
    mockMvc
        .perform(
            get("/")
                .param("desktop", "")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobi) Chrome"))
        .andExpect(status().isOk());
  }

  @Test
  void desktopUserAgentGetsTheLanding() throws Exception {
    mockMvc
        .perform(get("/").header("User-Agent", "Mozilla/5.0 (Macintosh) Chrome"))
        .andExpect(status().isOk());
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private long upload() throws Exception {
    mockMvc.perform(multipart("/receipts").file(jpegPart())).andExpect(status().is3xxRedirection());
    return jdbcClient
        .sql("select receipt_id from receipt order by receipt_id desc limit 1")
        .query(Long.class)
        .single();
  }

  private void setState(long id, String state) {
    jdbcClient
        .sql("update receipt set state = :state where receipt_id = :id")
        .param("state", state)
        .param(ID, id)
        .update();
  }

  private String originalPath(long id) {
    return jdbcClient
        .sql("select original_path from receipt where receipt_id = :id")
        .param(ID, id)
        .query(String.class)
        .single();
  }

  private boolean isLive(long id) {
    return jdbcClient
        .sql("select deleted_at is null from receipt where receipt_id = :id")
        .param(ID, id)
        .query(Boolean.class)
        .single();
  }

  private static MockMultipartFile jpegPart() {
    BufferedImage img = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(img, "jpg", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new MockMultipartFile("image", "photo.jpg", "image/jpeg", out.toByteArray());
  }

  private static Path tempRoot() {
    try {
      return Files.createTempDirectory("hauptbuch-register-test");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
