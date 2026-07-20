package volkovandr.hauptbuch.debts;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.AccountDraft;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * Integration tier (plan §1.5): {@code POST /people/resolve-account} driven against real Postgres —
 * the entry dock's person sub-field (register §3.3, plan stage 8b), which accepts a real account or
 * an <em>already-established</em> person's per-currency debt leaf, but never auto-provisions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PersonAccountResolveIntegrationTest {

  private static final String RESOLVE_PATH = "/people/resolve-account";
  private static final String EUR = "EUR";

  @Autowired MockMvc mockMvc;
  @Autowired AccountService accountService;
  @Autowired SettingsService settingsService;
  @Autowired PersonProvisioningService personProvisioningService;

  @BeforeEach
  void setUp() {
    settingsService.setBaseCurrency(EUR);
  }

  @Test
  void resolvesRealAccountByName() throws Exception {
    accountService.openAccount(
        new AccountDraft("Cash", "asset", null, EUR, LocalDate.now(), BigDecimal.ZERO));

    mockMvc
        .perform(post(RESOLVE_PATH).param("personAccountText", "Cash"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"personAccountId\"")))
        .andExpect(content().string(containsString("Cash (EUR)")));
  }

  @Test
  void resolvesAnEstablishedPersonsSoleLeafByName() throws Exception {
    personProvisioningService.ensureLeaf("Max", EUR, false);

    mockMvc
        .perform(post(RESOLVE_PATH).param("personAccountText", "Max"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"personAccountId\"")))
        // Displays the person's name, not the leaf's cosmetic internal name.
        .andExpect(content().string(containsString("Max (EUR)")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("personal.EUR"))));
  }

  @Test
  void disambiguatesMultiCurrencyPersonByCurrencySuffix() throws Exception {
    personProvisioningService.ensureLeaf("Max", EUR, false);
    personProvisioningService.ensureLeaf("Max", "CHF", false);

    mockMvc
        .perform(post(RESOLVE_PATH).param("personAccountText", "Max (CHF)"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Max (CHF)")));
  }

  @Test
  void rejectsPersonWithNoLeafYetPointingAtCategoryPath() throws Exception {
    mockMvc
        .perform(post(RESOLVE_PATH).param("personAccountText", "Ghost"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No account or person named")))
        .andExpect(content().string(containsString("name=\"personAccountId\" value=\"\"")));
  }
}
