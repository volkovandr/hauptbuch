package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Shared set-up for the statement screen tests: a profile for the sample bank CSV below, and the
 * two-step upload driven through MockMvc.
 */
final class StatementFixtures {

  /** Four lines: two good, one in another currency, one with an unreadable booking date. */
  static final String CSV =
      "Booking;Value;Amount;Currency;Counterparty;Text;Category;IBAN\n"
          + "02.05.2026;03.05.2026;-12,50;EUR;ShopAaa;Card payment;Groceries;XX00 1111 2222\n"
          + "05.05.2026;05.05.2026;1.234,56;EUR;Employer;Salary;Income;XX00 1111 2222\n"
          + "07.05.2026;;-9,00;USD;ShopBbb;Abroad;;XX00 1111 2222\n"
          + "soon;;-1,00;EUR;ShopCcc;Broken;;XX00 1111 2222\n";

  private StatementFixtures() {}

  /** Save the profile for {@link #CSV} through the screen and return its id. */
  static long saveProfile(MockMvc mockMvc, JdbcClient jdbcClient) throws Exception {
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
                .param("colValueDate", "Value")
                .param("colAmount", "Amount")
                .param("colCurrency", "Currency")
                .param("colCounterparty", "Counterparty")
                .param("colDescription", "Text")
                .param("colBankCategory", "Category")
                .param("colIban", "IBAN")
                .param("windowDaysBefore", "10")
                .param("windowDaysAfter", "3"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/statements/profiles"));
    return jdbcClient
        .sql("select statement_profile_id from statement_profile where name = 'BankAaa CSV'")
        .query(Long.class)
        .single();
  }

  /** Upload {@link #CSV} against the profile and return the confirm URL the screen redirects to. */
  static String upload(MockMvc mockMvc, long profileId) throws Exception {
    return upload(mockMvc, profileId, null);
  }

  /** As {@link #upload(MockMvc, long)}, with the account the operator picked on the form. */
  static String upload(MockMvc mockMvc, long profileId, Long accountId) throws Exception {
    MockMultipartHttpServletRequestBuilder request =
        multipart("/statements/upload")
            .file(
                new MockMultipartFile(
                    "file", "2026-05.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)))
            .param("profile", String.valueOf(profileId));
    if (accountId != null) {
      request.param("account", String.valueOf(accountId));
    }
    MvcResult upload =
        mockMvc
            .perform(request)
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("/statements/confirm?*"))
            .andReturn();
    return upload.getResponse().getRedirectedUrl();
  }

  /** The stored file's path carried by a confirm URL. */
  static String pathOf(String confirmUrl) {
    String path =
        UriComponentsBuilder.fromUriString(confirmUrl).build().getQueryParams().getFirst("path");
    return URLDecoder.decode(path, StandardCharsets.UTF_8);
  }

  /** Upload {@link #CSV}, confirm it onto {@code accountId}, and return the new statement's id. */
  static long uploadAndCreate(MockMvc mockMvc, long profileId, long accountId) throws Exception {
    String confirmUrl = upload(mockMvc, profileId);
    MvcResult confirm =
        mockMvc
            .perform(get(URI.create(confirmUrl)))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("4</span> lines")))
            .andExpect(content().string(containsString("02.05.2026")))
            .andExpect(content().string(containsString("07.05.2026")))
            .andExpect(content().string(containsString("Unreadable lines: <span>1</span>")))
            .andExpect(content().string(containsString("Proposed from the file")))
            .andReturn();
    assertThat(confirm.getResponse().getContentAsString())
        .containsPattern("value=\"" + accountId + "\"[^>]*selected");
    MvcResult created =
        mockMvc
            .perform(
                post("/statements/create")
                    .param("profileId", String.valueOf(profileId))
                    .param("path", pathOf(confirmUrl))
                    .param("name", "2026-05.csv")
                    .param("account", String.valueOf(accountId)))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    String redirect = Objects.requireNonNull(created.getResponse().getRedirectedUrl());
    return Long.parseLong(redirect.substring(redirect.lastIndexOf('/') + 1));
  }
}
