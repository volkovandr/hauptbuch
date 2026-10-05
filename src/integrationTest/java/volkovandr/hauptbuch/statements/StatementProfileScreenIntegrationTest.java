package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (CLAUDE.md §6): the statement profile screens of slice b1 driven through MockMvc
 * against real Postgres — a profile is saved and listed, a bad setting is refused on the editor,
 * and the live preview reads a sample with the settings as they stand in the form. Each test is
 * rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementProfileScreenIntegrationTest {

  private static final String CSV =
      "Booking;Value;Amount;Currency;Counterparty;Text;Category;IBAN\n"
          + "02.05.2026;03.05.2026;-12,50;EUR;ShopAaa;Card payment;Groceries;XX00 1111 2222\n"
          + "soon;;-1,00;EUR;ShopCcc;Broken;;XX00 1111 2222\n";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbcClient;

  private void saveProfile() throws Exception {
    mockMvc
        .perform(
            post("/statements/profiles/save")
                .param("name", "BankAaa CSV")
                .param("csvDelimiter", ";")
                .param("csvQuote", "\"")
                .param("csvEncoding", "UTF-8")
                .param("csvSkipRows", "0")
                .param("csvHasHeader", "true")
                .param("csvDecimalSeparator", ",")
                .param("csvDateFormat", "dd.MM.yyyy")
                .param("csvSignMode", "signed")
                .param("colBookingDate", "Booking")
                .param("colAmount", "Amount")
                .param("windowDaysBefore", "10")
                .param("windowDaysAfter", "3"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/statements/profiles"));
  }

  @Test
  void theProfileScreenSavesProfileAndListsIt() throws Exception {
    saveProfile();

    mockMvc
        .perform(get("/statements/profiles"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("BankAaa CSV")));
    mockMvc
        .perform(get("/statements/profiles/new"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("hx-post=\"/statements/profiles/preview\"")));
  }

  @Test
  void profileWithBadDateFormatIsRefusedOnTheEditor() throws Exception {
    mockMvc
        .perform(
            post("/statements/profiles/save")
                .param("name", "Bad")
                .param("csvDelimiter", ";")
                .param("csvQuote", "\"")
                .param("csvEncoding", "UTF-8")
                .param("csvDecimalSeparator", ",")
                .param("csvDateFormat", "dd.MM.'yyyy")
                .param("csvSignMode", "signed")
                .param("colBookingDate", "Booking")
                .param("colAmount", "Amount")
                .param("windowDaysBefore", "10")
                .param("windowDaysAfter", "3"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("The date format")));
    assertThat(
            jdbcClient
                .sql("select count(*) from statement_profile where name = 'Bad'")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void thePreviewParsesTheSampleWithTheFormsSettings() throws Exception {
    mockMvc
        .perform(
            multipart("/statements/profiles/preview")
                .file(
                    new MockMultipartFile(
                        "sample", "s.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)))
                .param("name", "p")
                .param("csvDelimiter", ";")
                .param("csvQuote", "\"")
                .param("csvEncoding", "UTF-8")
                .param("csvSkipRows", "0")
                .param("csvHasHeader", "true")
                .param("csvDecimalSeparator", ",")
                .param("csvDateFormat", "dd.MM.yyyy")
                .param("csvSignMode", "signed")
                .param("colBookingDate", "Booking")
                .param("colAmount", "Amount")
                .param("colCurrency", "Currency")
                .param("windowDaysBefore", "10")
                .param("windowDaysAfter", "3"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("2026-05-02")))
        .andExpect(content().string(containsString("-12.50")))
        .andExpect(content().string(containsString("Unreadable booking date")));
  }

  @Test
  void thePreviewNamesColumnTheHeaderDoesNotHave() throws Exception {
    mockMvc
        .perform(
            multipart("/statements/profiles/preview")
                .file(
                    new MockMultipartFile(
                        "sample", "s.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)))
                .param("name", "p")
                .param("csvDelimiter", ";")
                .param("csvQuote", "\"")
                .param("csvEncoding", "UTF-8")
                .param("csvHasHeader", "true")
                .param("csvDecimalSeparator", ",")
                .param("csvDateFormat", "dd.MM.yyyy")
                .param("csvSignMode", "signed")
                .param("colBookingDate", "Buchungstag")
                .param("colAmount", "Amount")
                .param("windowDaysBefore", "10")
                .param("windowDaysAfter", "3"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Buchungstag")))
        .andExpect(content().string(containsString("Found: Booking, Value, Amount")));
  }

  @Test
  void thePreviewAsksForSampleWhenNoneIsPicked() throws Exception {
    mockMvc
        .perform(multipart("/statements/profiles/preview").param("name", "p"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Pick a sample file")));
  }
}
