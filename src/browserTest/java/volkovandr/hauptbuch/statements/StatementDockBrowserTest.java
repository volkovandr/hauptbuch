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
    String csv =
        CSV_HEADER + "02.05.2026;;" + amount + ";EUR;BrowShopAaa;Card payment;Groceries;\n";
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
}
