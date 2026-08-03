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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (§1.5): the PC processing screen driven through its controller against real
 * Postgres and a temp storage root — the per-state views, the Save round-trip (multipart edited
 * image + note + recipe), Discard edits, the 3-way delete dialog, and delete-then-navigate. The
 * client-side Cropper editor itself is untested (no browser tier, standing rule); this covers the
 * server contract it posts against.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptProcessingScreenIntegrationTest {

  private static final Path STORAGE_ROOT = ReceiptImages.tempStorageRoot();
  private static final String QUERY = "?state=queue&range=d90";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;

  @DynamicPropertySource
  static void storageRoot(DynamicPropertyRegistry registry) {
    registry.add("hauptbuch.receipts.storage-root", STORAGE_ROOT::toString);
  }

  @Test
  void newReceiptShowsThePrepareForAnalysisView() throws Exception {
    long id = upload();

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Prepare for analysis")))
        // The editor form and its Save/Cancel are present (hidden until edit mode).
        .andExpect(content().string(containsString("data-receipt-cropper")))
        .andExpect(content().string(containsString("/js/cropper.min.js")));
  }

  @Test
  void missingReceiptRedirectsToTheRegister() throws Exception {
    mockMvc
        .perform(get("/receipts/999999"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/receipts"));
  }

  @Test
  void savePreProcessStoresTheEditNoteRecipeThenRedirects() throws Exception {
    long id = upload();

    mockMvc
        .perform(
            multipart("/receipts/" + id + "/pre-process")
                .file(ReceiptImages.editedJpegPart())
                .param("editRecipe", "{\"rotate\":90}")
                .param("aiNote", "this is fuel"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/receipts/" + id + QUERY));

    assertThat(stateOf(id)).isEqualTo("pre_processed");
    assertThat(columnOf(id, "edited_path")).startsWith("edited/");
    assertThat(columnOf(id, "edit_recipe")).isEqualTo("{\"rotate\":90}");
    assertThat(columnOf(id, "ai_note")).isEqualTo("this is fuel");
    // The edited image is on disk and served full-size at /edited.
    assertThat(Files.exists(STORAGE_ROOT.resolve(columnOf(id, "edited_path")))).isTrue();
    mockMvc.perform(get("/receipts/" + id + "/edited")).andExpect(status().isOk());
  }

  @Test
  void preProcessedReceiptShowsEditAndDiscardAndTheEditedImage() throws Exception {
    long id = uploadAndPreProcess();

    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(">Edit<")))
        .andExpect(content().string(containsString("Discard edits")))
        // The read view shows the edited image (the exact AI bytes), not the original.
        .andExpect(content().string(containsString("/receipts/" + id + "/edited")));
  }

  @Test
  void discardEditsReturnsToNewButKeepsTheNote() throws Exception {
    long id = uploadAndPreProcess();

    mockMvc
        .perform(post("/receipts/" + id + "/discard-edits"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/receipts/" + id + QUERY));

    assertThat(stateOf(id)).isEqualTo("new");
    assertThat(columnOf(id, "edited_path")).isNull();
    // The AI note survives the stage-undo.
    assertThat(columnOf(id, "ai_note")).isEqualTo("this is fuel");
  }

  @Test
  void committedReceiptScreenPointsDeleteAtTheFiveWayDialog() throws Exception {
    long id = upload();
    jdbcClient
        .sql("update receipt set state = 'committed' where receipt_id = :id")
        .param("id", id)
        .update();

    // A committed receipt's deletion is the transaction-aware 5-way dialog (§7, plan §9g), never
    // the ladder's 3-way rung (which would 500 on ReceiptService.delete's committed guard).
    mockMvc
        .perform(get("/receipts/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/delete-committed-confirm")))
        .andExpect(content().string(not(containsString("/delete-confirm?"))))
        // The chrome is out-of-band only in a Confirm/Reopen response — never on a page load, where
        // htmx would have nothing to swap it into.
        .andExpect(content().string(not(containsString("hx-swap-oob"))));
  }

  @Test
  void deleteConfirmRendersTheThreeWayDialog() throws Exception {
    long id = upload();

    mockMvc
        .perform(get("/receipts/" + id + "/delete-confirm"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Keep files")))
        .andExpect(content().string(containsString("Delete files too")));
  }

  @Test
  void deleteNavigatesToTheNextReceiptThenBackToTheRegister() throws Exception {
    long first = upload();
    long second = upload();

    // Deleting the earlier receipt lands on its next neighbour.
    mockMvc
        .perform(post("/receipts/" + first + "/delete").param("removeFiles", "true"))
        .andExpect(redirectedUrl("/receipts/" + second + QUERY));
    assertThat(isLive(first)).isFalse();

    // Deleting the last remaining receipt falls back to the register.
    mockMvc
        .perform(post("/receipts/" + second + "/delete").param("removeFiles", "true"))
        .andExpect(redirectedUrl("/receipts" + QUERY));
    assertThat(isLive(second)).isFalse();
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private long upload() throws Exception {
    mockMvc
        .perform(multipart("/receipts/upload").file(ReceiptImages.jpegPart()))
        .andExpect(status().is3xxRedirection());
    return jdbcClient
        .sql("select receipt_id from receipt order by receipt_id desc limit 1")
        .query(Long.class)
        .single();
  }

  private long uploadAndPreProcess() throws Exception {
    long id = upload();
    mockMvc
        .perform(
            multipart("/receipts/" + id + "/pre-process")
                .file(ReceiptImages.editedJpegPart())
                .param("editRecipe", "{}")
                .param("aiNote", "this is fuel"))
        .andExpect(status().is3xxRedirection());
    return id;
  }

  private String stateOf(long id) {
    return columnOf(id, "state");
  }

  private String columnOf(long id, String column) {
    return jdbcClient
        .sql("select " + column + " from receipt where receipt_id = :id")
        .param("id", id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private boolean isLive(long id) {
    return jdbcClient
        .sql("select deleted_at is null from receipt where receipt_id = :id")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }
}
