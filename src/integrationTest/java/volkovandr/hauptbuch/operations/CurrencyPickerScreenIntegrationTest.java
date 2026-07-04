package volkovandr.hauptbuch.operations;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * Integration tier (plan §1.5): the stage-6d currency picker's htmx endpoints driven through the
 * controller against real Postgres — the acceptance surface for "add a currency from any picker".
 * It proves the dialog opens, an add re-renders the picker with the new currency pre-selected (the
 * out-of-band swap), a duplicate re-renders the dialog with the error, and Cancel clears the mount.
 *
 * <p>{@code @Transactional} rolls each test back so an added currency does not leak across the
 * reused container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CurrencyPickerScreenIntegrationTest {

  private static final String BASE_CURRENCY = "baseCurrency";
  private static final String CURRENCY_CODE = "currencyCode";
  private static final String FIELD_ID = "fieldId";
  private static final String FIELD_NAME = "fieldName";

  @Autowired MockMvc mockMvc;

  @Test
  void opensTheDialogForTheRequestingPicker() throws Exception {
    mockMvc
        .perform(
            get("/currencies/new").param(FIELD_ID, BASE_CURRENCY).param(FIELD_NAME, BASE_CURRENCY))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Add a currency")))
        // The form and Cancel carry rendered htmx attributes (emitted via th:attr — there is no
        // htmx Thymeleaf dialect, so th:hx-* would be dropped and the dialog would be inert).
        .andExpect(content().string(containsString("hx-post=\"/currencies\"")))
        .andExpect(content().string(containsString("hx-target=\"#currency-dialog-baseCurrency\"")))
        .andExpect(
            content()
                .string(containsString("hx-get=\"/currencies/new/cancel?fieldId=baseCurrency\"")))
        // The field id/name ride through as hidden inputs so the response re-renders this picker.
        .andExpect(content().string(containsString("name=\"fieldId\"")))
        .andExpect(content().string(containsString("value=\"baseCurrency\"")));
  }

  @Test
  void addingCurrencyRerendersPickerWithItPreselected() throws Exception {
    mockMvc
        .perform(
            post("/currencies")
                .param(FIELD_ID, CURRENCY_CODE)
                .param(FIELD_NAME, CURRENCY_CODE)
                .param("code", "nok")
                .param("name", "Norwegian Krone")
                .param("symbol", "kr")
                .param("minorUnits", "2"))
        .andExpect(status().isOk())
        // The out-of-band picker swap comes back with the new currency present and pre-selected.
        .andExpect(content().string(containsString("id=\"currency-picker-currencyCode\"")))
        .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
        .andExpect(content().string(containsString("NOK — Norwegian Krone")))
        .andExpect(content().string(containsString("selected")));
  }

  @Test
  void rejectingDuplicateRerendersDialogWithError() throws Exception {
    mockMvc
        .perform(
            post("/currencies")
                .param(FIELD_ID, BASE_CURRENCY)
                .param(FIELD_NAME, BASE_CURRENCY)
                .param("code", "EUR")
                .param("name", "Euro")
                .param("symbol", "€")
                .param("minorUnits", "2"))
        .andExpect(status().isOk())
        // Back to the dialog, carrying the rejection — the picker is not swapped.
        .andExpect(content().string(containsString("Add a currency")))
        .andExpect(content().string(containsString("already exists")))
        .andExpect(content().string(not(containsString("hx-swap-oob"))));
  }

  @Test
  void cancelClearsTheDialogMount() throws Exception {
    mockMvc
        .perform(get("/currencies/new/cancel").param(FIELD_ID, CURRENCY_CODE))
        .andExpect(status().isOk())
        // The emptied mount — no dialog markup.
        .andExpect(content().string(not(containsString("Add a currency"))));
  }
}
