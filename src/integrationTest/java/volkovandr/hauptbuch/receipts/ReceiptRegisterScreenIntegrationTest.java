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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

  /**
   * The batch seam is faked so Process never reaches the network. The claim itself is synchronous
   * and asserted below; the background submit sees nothing (this test's rows are rolled back, never
   * committed), and with the seam faked that stays true however the executor is scheduled.
   */
  @MockitoBean ReceiptBatchClient receiptBatchClient;

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
        .andExpect(content().string(containsString("Txn date")))
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
        // Double-click opens the processing screen; the row carries its URL for the keyboard leaf,
        // including the active filter (issue tracker #10) so the processing screen resolves
        // prev/next over the same filter the row was opened from.
        .andExpect(
            content()
                .string(
                    containsString(
                        "data-receipt-open=\"/receipts/" + id + "?state=queue&amp;range=d90\"")));
  }

  @Test
  void registerRowOpenLinkCarriesNonDefaultFilter() throws Exception {
    long id = upload();
    setState(id, "committed");

    mockMvc
        .perform(get(RECEIPTS_PATH).param("state", "committed").param("range", "y1"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "data-receipt-open=\"/receipts/"
                            + id
                            + "?state=committed&amp;range=y1\"")));
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

  // ── Batch: the Process action (§3.2, plan §9h) ───────────────────────────────

  @Test
  void menuOffersProcessForPreProcessedMembers() throws Exception {
    long one = upload();
    long two = upload();
    setState(one, "pre_processed");
    setState(two, "pre_processed");

    mockMvc
        .perform(
            get("/receipts/menu").param(ID, String.valueOf(one)).param(ID, String.valueOf(two)))
        .andExpect(content().string(containsString("Process 2 receipts with AI")))
        .andExpect(content().string(containsString("/receipts/process")));
  }

  /** Nothing ready for the AI ⇒ no Process row at all (the action would be a no-op). */
  @Test
  void menuOmitsProcessWhenNothingIsPreProcessed() throws Exception {
    long id = upload();

    mockMvc
        .perform(get("/receipts/menu").param(ID, String.valueOf(id)))
        .andExpect(content().string(not(containsString("with AI"))));
  }

  @Test
  void menuReportsMembersProcessWouldSkip() throws Exception {
    long ready = upload();
    long fresh = upload();
    setState(ready, "pre_processed");

    mockMvc
        .perform(
            get("/receipts/menu").param(ID, String.valueOf(ready)).param(ID, String.valueOf(fresh)))
        .andExpect(content().string(containsString("Process 1 receipt with AI")))
        .andExpect(content().string(containsString("1 of 2 selected are not ready for the AI")));
  }

  /**
   * Process claims every valid member and skips the rest, then re-renders the list in place — the
   * claimed rows read {@code Processing}, the skipped one keeps its state. The submit itself runs
   * on the background executor, so nothing here touches the network.
   */
  @Test
  void processClaimsPreProcessedMembersAndSkipsTheRest() throws Exception {
    long ready = upload();
    long fresh = upload();
    setState(ready, "pre_processed");

    mockMvc
        .perform(
            post("/receipts/process")
                .param(ID, String.valueOf(ready))
                .param(ID, String.valueOf(fresh)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("rstate--processing")));

    assertThat(stateOf(ready)).isEqualTo("processing");
    assertThat(stateOf(fresh)).isEqualTo("new");
  }

  /** A batch member is marked in the register's status column, so a stalled job is visible. */
  @Test
  void registerBadgesBatchMembers() throws Exception {
    long id = upload();
    setState(id, "processing");
    jdbcClient
        .sql("update receipt set batch_id = 'msgbatch_01' where receipt_id = :id")
        .param(ID, id)
        .update();

    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(content().string(containsString("title=\"Batch member\"")))
        .andExpect(content().string(containsString("rstate--processing")));
  }

  // ── The list poll (issue tracker #03) ────────────────────────────────────────

  /**
   * Nothing watched has left {@code processing}: the response is the bare trigger fragment alone —
   * no {@code hx-swap-oob} marker anywhere, i.e. {@code #receipt-list} itself is never touched by
   * an unchanged tick (the htmx contract that keeps the owner's row selection alive, not just the
   * rendered text).
   */
  @Test
  void listStatusKeepsPollingWhenNothingChanged() throws Exception {
    long id = upload();
    setState(id, "processing");

    mockMvc
        .perform(get("/receipts/status").param(ID, String.valueOf(id)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"receipt-list-poll\"")))
        .andExpect(content().string(not(containsString("hx-swap-oob"))))
        .andExpect(content().string(not(containsString("<table"))));
  }

  /**
   * A watched receipt left {@code processing}: the response carries the out-of-band {@code
   * #receipt-list} refresh (the real htmx contract, not just updated text) reflecting the new
   * state, and — since nothing else is in flight — no trigger at all, so the poll stops.
   */
  @Test
  void listStatusRefreshesTheListOnceRowLeavesProcessing() throws Exception {
    long id = upload();
    setState(id, "processed");

    mockMvc
        .perform(get("/receipts/status").param(ID, String.valueOf(id)))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "id=\"receipt-list\" class=\"receipt-list\" hx-swap-oob=\"true\"")))
        .andExpect(content().string(containsString("rstate--processed")))
        .andExpect(content().string(not(containsString("id=\"receipt-list-poll\""))));
  }

  /**
   * One of two watched receipts finished; the other is still {@code processing}. The refreshed list
   * still carries a live trigger — watching the one still in flight — so the poll continues.
   */
  @Test
  void listStatusReArmsTheTriggerWhenOneOfSeveralIsStillProcessing() throws Exception {
    long finished = upload();
    long stillGoing = upload();
    setState(finished, "processed");
    setState(stillGoing, "processing");

    mockMvc
        .perform(
            get("/receipts/status")
                .param(ID, String.valueOf(finished))
                .param(ID, String.valueOf(stillGoing)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
        .andExpect(content().string(containsString("id=\"receipt-list-poll\"")))
        .andExpect(content().string(containsString("/receipts/status?id=" + stillGoing)));
  }

  @Test
  void registerRendersTheListPollWhenRowIsProcessing() throws Exception {
    long id = upload();
    setState(id, "processing");

    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(content().string(containsString("id=\"receipt-list-poll\"")))
        .andExpect(content().string(containsString("hx-trigger=\"every 10s\"")));
  }

  @Test
  void registerOmitsTheListPollWhenNothingIsProcessing() throws Exception {
    upload();

    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(content().string(not(containsString("id=\"receipt-list-poll\""))));
  }

  // ── Merchant column precedence (issue tracker #07) ───────────────────────────

  @Test
  void registerShowsTheAssignedPayeesNameOverTheRawParse() throws Exception {
    long id = upload();
    setMerchant(id, "REWE SAGT DANKE", null, null);
    long payeeId = insertPayee("Rewe", "Dortmund", null);
    setPayee(id, payeeId);

    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(content().string(containsString(">Rewe<")))
        .andExpect(content().string(not(containsString("REWE SAGT DANKE"))));
  }

  @Test
  void registerFallsBackToTheParsedMerchantCompositeWhenNoPayeeIsAssigned() throws Exception {
    long id = upload();
    setMerchant(id, null, "Berlin", "Germany");

    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(content().string(containsString(">Berlin - Germany<")));
  }

  @Test
  void registerShowsBlankMerchantWhenNothingWasParsedOrAssigned() throws Exception {
    upload();

    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(content().string(containsString("class=\"receipts__merchant\"></td>")));
  }

  /**
   * The owner's exact repro: assigning a payee via Save and returning to the list must show it
   * immediately, with no stale caching — the list re-queries fresh on every render.
   */
  @Test
  void registerReflectsPayeeAssignedAfterTheFirstListRender() throws Exception {
    long id = upload();
    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(content().string(containsString("class=\"receipts__merchant\"></td>")));

    long payeeId = insertPayee("Lidl", null, null);
    setPayee(id, payeeId);

    mockMvc.perform(get(RECEIPTS_PATH)).andExpect(content().string(containsString(">Lidl<")));
  }

  // ── Transaction-date column (issue tracker #09) ─────────────────────────────

  @Test
  void registerShowsTheLinkedTransactionsBookingDate() throws Exception {
    long id = upload();
    setState(id, "committed");
    linkTransaction(id, insertTransaction(java.time.LocalDate.of(2026, 6, 1)));

    mockMvc
        .perform(get(RECEIPTS_PATH).param("state", "all"))
        .andExpect(content().string(containsString(">2026-06-01<")));
  }

  @Test
  void registerShowsBlankTransactionDateWhenNotYetCommitted() throws Exception {
    upload();

    mockMvc
        .perform(get(RECEIPTS_PATH))
        .andExpect(content().string(containsString("class=\"receipts__txn-date num\"></td>")));
  }

  // ── Sortable headers (issue tracker #11) ────────────────────────────────────

  @Test
  void sortByCapturedAscendingReordersTheList() throws Exception {
    long later = upload();
    setCapturedAt(later, "2026-07-20T10:00:00Z");
    long earlier = upload();
    setCapturedAt(earlier, "2026-07-10T10:00:00Z");

    mockMvc
        .perform(get(RECEIPTS_PATH).param("sort", "captured").param("dir", "asc"))
        .andExpect(content().string(rowOrder(earlier, later)));
  }

  @Test
  void sortByTotalOrdersLargestFirstByDefault() throws Exception {
    long small = upload();
    setTotal(small, "5.00");
    long large = upload();
    setTotal(large, "50.00");

    mockMvc
        .perform(get(RECEIPTS_PATH).param("sort", "total"))
        .andExpect(content().string(rowOrder(large, small)));
  }

  @Test
  void sortByMerchantOrdersAlphabeticallyByDefaultOverTheResolvedDisplay() throws Exception {
    long apple = upload();
    setMerchant(apple, "Apple Store", null, null);
    long zebra = upload();
    setMerchant(zebra, "Zebra Shop", null, null);

    mockMvc
        .perform(get(RECEIPTS_PATH).param("sort", "merchant"))
        .andExpect(content().string(rowOrder(apple, zebra)));
  }

  @Test
  void sortByTxnDateOrdersOverTheLinkedTransactionsBookingDate() throws Exception {
    long later = upload();
    setState(later, "committed");
    linkTransaction(later, insertTransaction(java.time.LocalDate.of(2026, 6, 20)));
    long earlier = upload();
    setState(earlier, "committed");
    linkTransaction(earlier, insertTransaction(java.time.LocalDate.of(2026, 6, 10)));

    mockMvc
        .perform(
            get(RECEIPTS_PATH).param("state", "all").param("sort", "txn_date").param("dir", "asc"))
        .andExpect(content().string(rowOrder(earlier, later)));
  }

  @Test
  void sortByTxnDatePlacesNotYetCommittedReceiptsLastAscending() throws Exception {
    long committed = upload();
    setState(committed, "committed");
    linkTransaction(committed, insertTransaction(java.time.LocalDate.of(2026, 6, 10)));
    long notCommitted = upload();

    mockMvc
        .perform(
            get(RECEIPTS_PATH).param("state", "all").param("sort", "txn_date").param("dir", "asc"))
        .andExpect(content().string(rowOrder(committed, notCommitted)));
  }

  @Test
  void invalidSortAndDirFallBackToTheDefaultCapturedDescendingOrder() throws Exception {
    upload();

    mockMvc
        .perform(get(RECEIPTS_PATH).param("sort", "bogus").param("dir", "bogus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("aria-sort=\"descending\"")))
        .andExpect(content().string(not(containsString("aria-sort=\"ascending\""))));
  }

  @Test
  void activeColumnShowsTheDirectionalGlyphAndCarriesTheSortInHiddenFields() throws Exception {
    upload();

    mockMvc
        .perform(get(RECEIPTS_PATH).param("sort", "total").param("dir", "asc"))
        .andExpect(content().string(containsString("aria-sort=\"ascending\"")))
        .andExpect(content().string(containsString(">Total<span")))
        .andExpect(content().string(containsString("> ↑</span")))
        .andExpect(content().string(containsString("name=\"sort\" value=\"total\"")))
        .andExpect(content().string(containsString("name=\"dir\" value=\"asc\"")));
  }

  // ── Voided-transaction badge and filter (issue tracker #08) ─────────────────

  @Test
  void registerShowsGreyVoidBadgeForCommittedReceiptWhoseTransactionWasVoided() throws Exception {
    long id = upload();
    setState(id, "committed");
    long transactionId = insertTransaction();
    linkTransaction(id, transactionId);
    voidTransactionDirectly(transactionId);

    mockMvc
        .perform(get(RECEIPTS_PATH).param("state", "all"))
        .andExpect(content().string(containsString("rstate--void")))
        .andExpect(content().string(containsString(">Void<")))
        .andExpect(content().string(not(containsString("rstate--committed"))));
  }

  @Test
  void registerKeepsTheOrdinaryCommittedBadgeWhenTheTransactionIsStillLive() throws Exception {
    long id = upload();
    setState(id, "committed");
    linkTransaction(id, insertTransaction());

    mockMvc
        .perform(get(RECEIPTS_PATH).param("state", "all"))
        .andExpect(content().string(containsString("rstate--committed")))
        .andExpect(content().string(not(containsString("rstate--void"))));
  }

  @Test
  void voidedFilterReturnsExactlyTheCommittedAndVoidedReceipts() throws Exception {
    long voided = upload();
    setState(voided, "committed");
    long voidedTxn = insertTransaction();
    linkTransaction(voided, voidedTxn);
    voidTransactionDirectly(voidedTxn);

    long stillLive = upload();
    setState(stillLive, "committed");
    linkTransaction(stillLive, insertTransaction());

    long notCommitted = upload();

    mockMvc
        .perform(get(RECEIPTS_PATH).param("state", "voided"))
        .andExpect(content().string(containsString("data-receipt-id=\"" + voided + "\"")))
        .andExpect(content().string(not(containsString("data-receipt-id=\"" + stillLive + "\""))))
        .andExpect(
            content().string(not(containsString("data-receipt-id=\"" + notCommitted + "\""))));
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

  /**
   * Backdate a receipt's capture instant (bypassing the {@code now()} default) — needed for the
   * sort tests (issue tracker #11): every upload in one test method shares a transaction, so {@code
   * now()} alone can't tell two captures apart.
   */
  private void setCapturedAt(long id, String instant) {
    jdbcClient
        .sql("update receipt set captured_at = :capturedAt where receipt_id = :id")
        .param("capturedAt", java.time.OffsetDateTime.parse(instant))
        .param(ID, id)
        .update();
  }

  private void setTotal(long id, String total) {
    jdbcClient
        .sql("update receipt set total_amount = :total where receipt_id = :id")
        .param("total", new java.math.BigDecimal(total))
        .param(ID, id)
        .update();
  }

  /** Matches when {@code first}'s row appears before {@code second}'s in the rendered list. */
  private org.hamcrest.Matcher<String> rowOrder(long first, long second) {
    return org.hamcrest.Matchers.matchesPattern(
        "(?s).*data-receipt-id=\"" + first + "\".*data-receipt-id=\"" + second + "\".*");
  }

  private void setMerchant(
      long id, String merchantText, String merchantCity, String merchantCountry) {
    jdbcClient
        .sql(
            """
            update receipt
               set merchant_text = :merchantText,
                   merchant_city = :merchantCity,
                   merchant_country = :merchantCountry
             where receipt_id = :id
            """)
        .param("merchantText", merchantText)
        .param("merchantCity", merchantCity)
        .param("merchantCountry", merchantCountry)
        .param(ID, id)
        .update();
  }

  private void setPayee(long id, long payeeId) {
    jdbcClient
        .sql("update receipt set payee_id = :payeeId where receipt_id = :id")
        .param("payeeId", payeeId)
        .param(ID, id)
        .update();
  }

  private long insertPayee(String name, String city, String countryCode) {
    return jdbcClient
        .sql(
            "insert into payee (name, city, country_code) values (:name, :city, :countryCode) "
                + "returning payee_id")
        .param("name", name)
        .param("city", city)
        .param("countryCode", countryCode)
        .query(Long.class)
        .single();
  }

  private long insertTransaction() {
    return jdbcClient
        .sql("insert into transaction (date) values (current_date) returning transaction_id")
        .query(Long.class)
        .single();
  }

  private long insertTransaction(java.time.LocalDate date) {
    return jdbcClient
        .sql("insert into transaction (date) values (:date) returning transaction_id")
        .param("date", date)
        .query(Long.class)
        .single();
  }

  private void linkTransaction(long receiptId, long transactionId) {
    jdbcClient
        .sql("update receipt set transaction_id = :tid where receipt_id = :id")
        .param("tid", transactionId)
        .param(ID, receiptId)
        .update();
  }

  /**
   * A bare soft-delete of a transaction, standing in for the register's own void action (whose
   * engine round-trip is already proven in {@code LedgerServiceTest}/{@code
   * RepositoryRoundTripIntegrationTest}) — kept a plain SQL update rather than an autowired {@code
   * LedgerService} so this screen test stays focused on rendering, not re-proving the engine.
   */
  private void voidTransactionDirectly(long transactionId) {
    jdbcClient
        .sql("update transaction set deleted_at = now() where transaction_id = :id")
        .param(ID, transactionId)
        .update();
  }

  private void setState(long id, String state) {
    jdbcClient
        .sql("update receipt set state = :state where receipt_id = :id")
        .param("state", state)
        .param(ID, id)
        .update();
  }

  private String stateOf(long id) {
    return jdbcClient
        .sql("select state from receipt where receipt_id = :id")
        .param(ID, id)
        .query(String.class)
        .single();
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
