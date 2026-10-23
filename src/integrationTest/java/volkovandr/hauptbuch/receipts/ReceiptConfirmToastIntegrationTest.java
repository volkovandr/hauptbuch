package volkovandr.hauptbuch.receipts;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier: the "Confirmed!" toast a successful Confirm leaves for the next page (receipt
 * issue 38) — recorded in the HTTP session on success only, rendered by the shared layout with an
 * edit link and the Ctrl+Backspace target, and silenced by a duration of 0.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ReceiptConfirmToastIntegrationTest {

  private static final Path STORAGE_ROOT = ReceiptImages.tempStorageRoot();
  private static final String EUR = "EUR";
  private static final String DAY = "2026-08-03";
  private static final String TOAST_CONTAINER = "class=\"confirm-toasts\"";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;
  @Autowired SettingsService settingsService;

  @DynamicPropertySource
  static void storageRoot(DynamicPropertyRegistry registry) {
    registry.add("hauptbuch.receipts.storage-root", STORAGE_ROOT::toString);
  }

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  @Test
  void confirmLeavesToastWithEditLinkOnTheNextPage() throws Exception {
    long id = processedReceipt();
    MockHttpSession session = new MockHttpSession();

    mockMvc
        .perform(confirm(id, "42,14").param("payeeText", "ShopAaa").session(session))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/receipts").session(session))
        .andExpect(content().string(containsString(TOAST_CONTAINER)))
        .andExpect(content().string(containsString("03.08.2026")))
        .andExpect(content().string(containsString("ShopAaa")))
        .andExpect(content().string(containsString("42,14")))
        .andExpect(content().string(containsString("href=\"/receipts/" + id + "\"")))
        // The newest toast's link rides on the container for the Ctrl+Backspace jump.
        .andExpect(
            content().string(containsString("data-last-confirmed=\"/receipts/" + id + "\"")));
  }

  @Test
  void refusedConfirmLeavesNoToast() throws Exception {
    long id = processedReceipt();
    MockHttpSession session = new MockHttpSession();

    mockMvc.perform(confirm(id, "99,00").session(session)).andExpect(status().isOk());

    mockMvc
        .perform(get("/receipts").session(session))
        .andExpect(content().string(not(containsString(TOAST_CONTAINER))));
  }

  @Test
  void toastDurationOfZeroSwitchesToastsOff() throws Exception {
    settingsService.setConfirmToastSeconds(0);
    long id = processedReceipt();
    MockHttpSession session = new MockHttpSession();

    mockMvc.perform(confirm(id, "42,14").session(session)).andExpect(status().isOk());

    mockMvc
        .perform(get("/receipts").session(session))
        .andExpect(content().string(not(containsString(TOAST_CONTAINER))));
  }

  /** A Confirm POST for the receipt's one Fuel line, with the header total as typed. */
  private MockHttpServletRequestBuilder confirm(long id, String total) {
    return post("/receipts/" + id + "/confirm")
        .param("date", DAY)
        .param("accountId", String.valueOf(account("Cash", "asset")))
        .param("currencyCode", EUR)
        .param("total", total)
        .param("lineDescription", "Diesel")
        .param("categoryText", "Fuel")
        .param("lineCategoryId", String.valueOf(account("Fuel", "expense")))
        .param("lineCategoryType", "expense")
        .param("lineAmount", "42,14");
  }

  private long processedReceipt() {
    return jdbcClient
        .sql(
            """
            insert into receipt (state, source, original_path, edited_path, edit_recipe,
                                 account_id, total_amount, currency_code, receipt_date)
            values ('processed', 'pc', 'originals/a.jpg', 'edited/a.jpg', '{}',
                    :account, 42.14, 'EUR', :day)
            returning receipt_id
            """)
        .param("account", account("Cash", "asset"))
        .param("day", LocalDate.parse(DAY))
        .query(Long.class)
        .single();
  }

  /** The account of that name, created on first use (the confirm needs a funding and a line). */
  private long account(String name, String type) {
    return jdbcClient
        .sql("select account_id from account where name = :name and type = :type")
        .param("name", name)
        .param("type", type)
        .query(Long.class)
        .optional()
        .orElseGet(
            () ->
                jdbcClient
                    .sql(
                        "insert into account (name, type, currency_code) values (:n, :t, 'EUR')"
                            + " returning account_id")
                    .param("n", name)
                    .param("t", type)
                    .query(Long.class)
                    .single());
  }
}
