package volkovandr.hauptbuch.statements;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import volkovandr.hauptbuch.BrowserTest;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The statement page's dock in a real browser (statements.md §6.4, slice d1): it opens directly
 * under the line it creates, and the category and tag pickers resolve inside it — the htmx
 * fragments they swap must survive the dock form's own whole-page swap settings, which MockMvc
 * cannot see. Each test creates its own statement, since the app commits.
 */
class StatementDockBrowserTest extends BrowserTest {

  private static final String CSV_HEADER =
      "Booking;Value;Amount;Currency;Counterparty;Text;Category;IBAN\n";

  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired StatementProfileService profileService;
  @Autowired volkovandr.hauptbuch.ledger.ExchangeRateService exchangeRateService;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

  private long accountId;

  @BeforeAll
  void seed() {
    if (settingsService.baseCurrency().isEmpty()) {
      settingsService.setBaseCurrency("EUR");
    }
    accountId =
        accountService
            .openAccount(
                new AccountDraft(
                    "BrowBank-EUR",
                    "asset",
                    null,
                    "EUR",
                    LocalDate.parse("2026-01-01"),
                    BigDecimal.ZERO))
            .accountId();
    accountService.insertLeaf("BrowFood", "expense", null, "EUR");
  }

  /** Save the profile, upload the CSV and confirm it; the new statement's page URL. */
  private String createStatement(String amount) {
    return createStatementFromRows(
        "02.05.2026;;" + amount + ";EUR;BrowShopAaa;Card payment;Groceries;\n");
  }

  private String createStatementFromRows(String rows) {
    String csv = CSV_HEADER + rows;
    page.request()
        .post(
            url("/statements/profiles/save"),
            RequestOptions.create()
                .setForm(
                    FormData.create()
                        .set("name", "BrowBank CSV")
                        .set("csvDelimiter", ";")
                        .set("csvQuote", "\"")
                        .set("csvEncoding", "UTF-8")
                        .set("csvSkipRows", "0")
                        .set("csvHasHeader", "true")
                        .set("csvDecimalSeparator", ",")
                        .set("csvDateFormat", "dd.MM.yyyy")
                        .set("csvSignMode", "signed")
                        .set("colBookingDate", "Booking")
                        .set("colValueDate", "Value")
                        .set("colAmount", "Amount")
                        .set("colCurrency", "Currency")
                        .set("colCounterparty", "Counterparty")
                        .set("colDescription", "Text")
                        .set("colBankCategory", "Category")
                        .set("colIban", "IBAN")
                        .set("windowDaysBefore", "10")
                        .set("windowDaysAfter", "3")));
    long profileId =
        profileService.live().stream()
            .filter(p -> "BrowBank CSV".equals(p.name()))
            .mapToLong(StatementProfile::statementProfileId)
            .max()
            .orElseThrow();
    String confirmUrl =
        page.request()
            .post(
                url("/statements/upload"),
                RequestOptions.create()
                    .setMultipart(
                        FormData.create()
                            .set("profile", String.valueOf(profileId))
                            .set(
                                "file",
                                new FilePayload(
                                    "2026-05.csv",
                                    "text/csv",
                                    csv.getBytes(StandardCharsets.UTF_8)))))
            .url();
    String path =
        Arrays.stream(URI.create(confirmUrl).getRawQuery().split("&"))
            .filter(pair -> pair.startsWith("path="))
            .map(
                pair -> URLDecoder.decode(pair.substring("path=".length()), StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    return page.request()
        .post(
            url("/statements/create"),
            RequestOptions.create()
                .setForm(
                    FormData.create()
                        .set("profileId", String.valueOf(profileId))
                        .set("path", path)
                        .set("name", "2026-05.csv")
                        .set("account", String.valueOf(accountId))))
        .url();
  }

  private void settled() {
    page.waitForFunction(
        "() => !document.querySelector('.htmx-request, .htmx-swapping, .htmx-settling')");
  }

  /**
   * Open the dock on a fresh statement. Every test books a different amount: a transaction one test
   * commits would otherwise match another test's identical line.
   */
  private void openDock(String amount) {
    page.navigate(createStatement(amount));
    page.getByText("Create", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true))
        .click();
    assertThat(page.locator("table.statements #statement-dock")).isVisible();
    // htmx wires the swapped-in dock after it is visible; an event fired earlier does nothing.
    settled();
  }

  @Test
  void dockOpensUnderTheLineAndTheCategoryAndTagPickersResolveInsideIt() {
    openDock("-12,50");

    Locator category = page.locator("#dock-category");
    category.fill("BrowFood");
    category.dispatchEvent("change");
    settled();
    assertThat(page.locator("#statement-dock input[name=categoryId]")).not().hasValue("");

    Locator tags = page.locator("#entry-tags");
    tags.fill("BrowTag");
    tags.press("Enter");
    settled();
    assertThat(page.locator("#statement-dock [data-tag-chip]")).hasCount(1);

    page.getByText("Save and match").click();
    assertThat(page.locator(".statements__notice"))
        .containsText("Transaction created and matched.");

    double unmatchTop = top("button:has-text('Unmatch')");
    double editTop = top("a:has-text('Edit in register')");
    org.assertj.core.api.Assertions.assertThat(editTop).isBetween(unmatchTop - 1, unmatchTop + 1);
  }

  private double top(String selector) {
    return edge(selector, "top");
  }

  private double left(String selector) {
    return edge(selector, "left");
  }

  private double right(String selector) {
    return edge(selector, "right");
  }

  private double edge(String selector, String side) {
    Object y = page.locator(selector).first().evaluate("e => e.getBoundingClientRect()." + side);
    return y instanceof Number n ? n.doubleValue() : -1;
  }

  @Test
  void extrasAccountPickerSitsLeftOfItsMoveButtonOnTheSameLine() {
    bookExtra("66,31");
    page.navigate(createStatement("-16,50"));

    double pickerTop = top("select[aria-label='Move to account']");
    double moveTop = top("button:has-text('Move')");
    double pickerRight = right("select[aria-label='Move to account']");
    double moveLeft = left("button:has-text('Move')");
    org.assertj.core.api.Assertions.assertThat(pickerTop).isBetween(moveTop - 8, moveTop + 8);
    org.assertj.core.api.Assertions.assertThat(pickerRight).isLessThanOrEqualTo(moveLeft);
  }

  @Test
  void extrasActionsHelpBubbleIsFullyVisibleOnHover() {
    bookExtra("67,31");
    page.navigate(createStatement("-17,50"));

    page.locator(".statement-lines--extras thead .help").hover();
    Object visible =
        page.locator(".statement-lines--extras thead .help__text")
            .evaluate(
                "e => { const r = e.getBoundingClientRect(); const hit ="
                    + " document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);"
                    + " return r.height > 40 && e.contains(hit); }");
    org.assertj.core.api.Assertions.assertThat(visible).isEqualTo(true);
  }

  @Test
  void pickingTransferIntoAnotherCurrencyRevealsTheCounterpartAmountFromTheRate() {
    accountService.openAccount(
        new AccountDraft(
            "BrowUsd-USD", "asset", null, "USD", LocalDate.parse("2026-01-01"), BigDecimal.ZERO));
    exchangeRateService.recordEnteredRate(
        LocalDate.parse("2026-05-01"), "USD", new BigDecimal("100.00"), new BigDecimal("90.00"));
    openDock("-18,00");

    Object option =
        page.evaluate(
            "Array.from(document.querySelectorAll('#dock-category-options option'))"
                + ".map(o => o.value).find(v => v.startsWith('To') && v.includes('BrowUsd'))");
    org.assertj.core.api.Assertions.assertThat(option).isNotNull();
    Locator category = page.locator("#dock-category");
    category.fill(String.valueOf(option));
    category.dispatchEvent("change");
    settled();

    assertThat(page.locator("#dock-category-amount")).hasValue("20,00");
    assertThat(page.locator("#dock-base-amount")).hasCount(0);

    category.fill("BrowFood");
    category.dispatchEvent("change");
    settled();
    assertThat(page.locator("#dock-category-amount")).hasCount(0);
  }

  @Test
  void dockOpensWithTheDateFocused() {
    openDock("-15,50");

    assertThat(page.locator("#dock-date")).isFocused();
  }

  @Test
  void newCategoryUnderAnExistingParentIsOfferedAndCreatedFromTheDock() {
    openDock("-13,50");

    Locator category = page.locator("#dock-category");
    category.fill("BrowFood - BrowChild");
    category.dispatchEvent("change");
    settled();
    assertThat(page.locator("#statement-dock .warning")).containsText("BrowChild");

    page.locator("#statement-dock .warning button").click();
    settled();
    assertThat(page.locator("#statement-dock input[name=categoryId]")).not().hasValue("");
  }

  @Test
  void refusedSaveKeepsWhatWasTypedAndShowsTheReasonBesideSave() {
    openDock("-14,50");

    page.locator("#dock-note").fill("typed note");
    page.getByText("Save and match").click();

    assertThat(page.locator("#statement-dock .statements__error"))
        .containsText("A category, transfer target, or person is required");
    assertThat(page.locator("#dock-note")).hasValue("typed note");
  }

  @Test
  void openingAndSavingTheDockDoesNotMoveThePage() {
    StringBuilder rows = new StringBuilder(2048);
    for (int i = 0; i < 40; i++) {
      rows.append("02.05.2026;;-").append(100 + i).append(",07;EUR;BrowShopAaa;Card;Groceries;\n");
    }
    for (int i = 0; i < 6; i++) {
      bookExtra("7" + i + ",31");
    }
    page.navigate(createStatementFromRows(rows.toString()));
    Locator create =
        page.getByText(
                "Create", new com.microsoft.playwright.Page.GetByTextOptions().setExact(true))
            .nth(30);
    create.scrollIntoViewIfNeeded();
    page.evaluate("window.scrollBy(0, -150)");
    double before = scrollY();
    org.assertj.core.api.Assertions.assertThat(before).isGreaterThan(300);
    create.click();
    assertThat(page.locator("table.statements #statement-dock")).isVisible();
    settled();
    double afterOpen = scrollY();
    org.assertj.core.api.Assertions.assertThat(afterOpen).isBetween(before - 5, before + 5);

    page.locator("#dock-category").fill("BrowFood");
    page.locator("#dock-category").dispatchEvent("change");
    settled();
    page.getByText("Save and match").click();
    assertThat(page.locator(".statements__notice")).isVisible();
    settled();
    double afterSave = scrollY();
    org.assertj.core.api.Assertions.assertThat(afterSave).isBetween(before - 5, before + 5);
  }

  private double scrollY() {
    Object y = page.evaluate("window.scrollY");
    return y instanceof Number n ? n.doubleValue() : 0;
  }

  /** A confirmed expense on the statement's account that no statement line mentions. */
  private void bookExtra(String amount) {
    long food =
        jdbcClient
            .sql("select account_id from account where name = 'BrowFood-EUR' or name = 'BrowFood'")
            .query(Long.class)
            .list()
            .get(0);
    long transaction =
        jdbcClient
            .sql(
                "insert into transaction (date, lifecycle) values (:d, 'confirmed') returning"
                    + " transaction_id")
            .param("d", LocalDate.of(2026, 5, 2))
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount) values (:t, :a, :m), (:t, :f,"
                + " :n)")
        .param("t", transaction)
        .param("a", accountId)
        .param("m", new BigDecimal("-" + amount.replace(',', '.')))
        .param("f", food)
        .param("n", new BigDecimal(amount.replace(',', '.')))
        .update();
  }
}
