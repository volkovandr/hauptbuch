package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): the entry dock's one fixed field order (issue
 * transaction-register-ui/04) — Date · Account · Payee · Currency · Amount · Off account · Base ·
 * Category · Note · Tags, whatever the currencies, with the split header keeping the same order —
 * and the Amount / Off account mapping onto the legs surviving a save → edit → save round trip.
 *
 * <p>{@code @Transactional} rolls each test back on the reused container — including the write-once
 * base-currency set in {@code @BeforeEach}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterFieldOrderIntegrationTest {

  private static final String ENTRY_PATH = "/register/entry";
  private static final String EUR = "EUR";
  private static final String DATE = "2026-02-01";
  private static final String ACCOUNT_ID = "accountId";
  private static final String VIEW_ACCOUNT_ID = "viewAccountId";
  private static final String CATEGORY_ID = "categoryId";
  private static final String CURRENCY = "categoryCurrencyCode";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired JdbcClient jdbcClient;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  private long openAccount(String name, String currencyCode) {
    return accountService
        .openAccount(
            new AccountDraft(
                name,
                "asset",
                null,
                currencyCode,
                LocalDate.parse("2026-01-01"),
                new BigDecimal("500")))
        .accountId();
  }

  private long insertCategory(String name) {
    return accountService.insertLeaf(name, "expense", null, EUR).accountId();
  }

  private long latestTransactionId() {
    return jdbcClient.sql("select max(transaction_id) from transaction").query(Long.class).single();
  }

  private List<BigDecimal> postingAmounts(long transactionId) {
    return jdbcClient
        .sql("select amount from posting where transaction_id = :t order by amount")
        .param("t", transactionId)
        .query(BigDecimal.class)
        .list();
  }

  /** Assert each marker occurs in {@code html}, in the given order. */
  private static void assertInOrder(String html, String... markers) {
    int previous = -1;
    for (String marker : markers) {
      int at = html.indexOf(marker);
      assertThat(at).as(marker).isGreaterThan(previous);
      previous = at;
    }
  }

  @Test
  void dockRendersItsFieldsInTheFixedOrder() throws Exception {
    long cash = openAccount("Cash", EUR);

    String html =
        mockMvc
            .perform(get("/register").param(VIEW_ACCOUNT_ID, String.valueOf(cash)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertInOrder(
        html,
        "id=\"entry-date\"",
        "id=\"entry-account\"",
        "id=\"entry-payee\"",
        "id=\"entry-category-currency\"",
        "id=\"entry-amount\"",
        "id=\"entry-category\"",
        "id=\"entry-note\"",
        "id=\"entry-tags\"");
  }

  @Test
  void crossCurrencyDockKeepsTheOrderWithOffAccountAndBaseAfterTheAmount() throws Exception {
    long chfCard = openAccount("Cash CHF", "CHF");
    long shopping = insertCategory("Shopping");
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", DATE)
                .param(ACCOUNT_ID, String.valueOf(chfCard))
                .param("amount", "11")
                .param(CATEGORY_ID, String.valueOf(shopping))
                .param(CURRENCY, "USD")
                .param("offAccountAmount", "10")
                .param("baseAmount", "9,50")
                .param(VIEW_ACCOUNT_ID, String.valueOf(chfCard)))
        .andExpect(status().isOk());

    String html =
        mockMvc
            .perform(
                get("/register/edit/" + latestTransactionId())
                    .param(VIEW_ACCOUNT_ID, String.valueOf(chfCard)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertInOrder(
        html,
        "id=\"entry-payee\"",
        "id=\"entry-category-currency\"",
        "Amount (USD)",
        "value=\"11,00\"",
        "Off account (CHF)",
        "value=\"10,00\"",
        "Base (EUR)",
        "value=\"9,50\"",
        "id=\"entry-category\"",
        "id=\"entry-note\"");
  }

  @Test
  void splitHeaderKeepsTheDockOrderAndSeedsFromTheDocksAmounts() throws Exception {
    long cash = openAccount("Cash", EUR);
    long food = insertCategory("Food");

    String html =
        mockMvc
            .perform(
                post("/register/split")
                    .param("date", DATE)
                    .param(ACCOUNT_ID, String.valueOf(cash))
                    .param("payeeText", "ShopAaa")
                    .param(CURRENCY, "USD")
                    .param("amount", "12")
                    .param("offAccountAmount", "11,00")
                    .param(CATEGORY_ID, String.valueOf(food))
                    .param("categoryText", "Food"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The dock's USD Amount becomes the line and Total; its Off account the header's EUR total.
    assertInOrder(
        html,
        "id=\"split-date\"",
        "id=\"split-account\"",
        "id=\"split-payee\"",
        "id=\"split-spending-currency\"",
        "id=\"split-total\"",
        "id=\"split-funding-total\"",
        "value=\"11,00\"",
        "id=\"split-note\"",
        "id=\"split-tags\"");
  }

  @Test
  void crossCurrencyRefundRoundTripsThroughEditWithoutFlippingTheSign() throws Exception {
    long cash = openAccount("Cash", EUR);
    long food = insertCategory("Food");
    // A refund of a CHF purchase onto a EUR account: the explicit − on the Amount (register §3.8)
    // makes the funding leg an inflow.
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("date", DATE)
                .param(ACCOUNT_ID, String.valueOf(cash))
                .param("amount", "−10")
                .param(CATEGORY_ID, String.valueOf(food))
                .param(CURRENCY, "CHF")
                .param("offAccountAmount", "9,10")
                .param(VIEW_ACCOUNT_ID, String.valueOf(cash)))
        .andExpect(status().isOk());
    long txnId = latestTransactionId();
    List<BigDecimal> booked = postingAmounts(txnId);
    assertThat(booked).anySatisfy(a -> assertThat(a).isEqualByComparingTo("9.10"));

    String html =
        mockMvc
            .perform(get("/register/edit/" + txnId).param(VIEW_ACCOUNT_ID, String.valueOf(cash)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertInOrder(html, "Amount (CHF)", "value=\"-10,00\"", "Off account (EUR)", "value=\"9,10\"");

    // Saving the edit exactly as shown books the same legs.
    mockMvc
        .perform(
            post(ENTRY_PATH)
                .param("transactionId", String.valueOf(txnId))
                .param("date", DATE)
                .param(ACCOUNT_ID, String.valueOf(cash))
                .param("amount", "−10,00")
                .param(CATEGORY_ID, String.valueOf(food))
                .param(CURRENCY, "CHF")
                .param("offAccountAmount", "9,10")
                .param(VIEW_ACCOUNT_ID, String.valueOf(cash)))
        .andExpect(status().isOk());
    assertThat(postingAmounts(txnId))
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactlyElementsOf(booked);
  }
}
