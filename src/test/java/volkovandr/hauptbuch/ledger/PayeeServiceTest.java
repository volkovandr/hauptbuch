package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.repository.CountryRepository;
import volkovandr.hauptbuch.ledger.repository.PayeeRepository;

/**
 * Unit tier (plan §1.5): {@link PayeeService}'s create-new parsing (register §3.4) — the logic that
 * tells a country segment from a city, and the resolve-existing-or-create decision. Pure over the
 * country resolver and payee insert, both mocked; no container (CLAUDE.md §6).
 */
@ExtendWith(MockitoExtension.class)
class PayeeServiceTest {

  @Mock private PayeeRepository payeeRepository;
  @Mock private CountryRepository countryRepository;
  @InjectMocks private PayeeService payeeService;

  @Test
  void parsesNameCityAndCountryFromThreeSegments() {
    when(countryRepository.resolveAlias("Germany")).thenReturn(Optional.of("DEU"));

    PayeeDraft draft = payeeService.parseCreateNew("Rewe - Dortmund - Germany");

    assertThat(draft.name()).isEqualTo("Rewe");
    assertThat(draft.city()).isEqualTo("Dortmund");
    assertThat(draft.countryCode()).isEqualTo("DEU");
  }

  @Test
  void parsesNameAndCountryWithNoCityWhenLastSegmentResolves() {
    when(countryRepository.resolveAlias("France")).thenReturn(Optional.of("FRA"));

    PayeeDraft draft = payeeService.parseCreateNew("Lidl - France");

    assertThat(draft.name()).isEqualTo("Lidl");
    assertThat(draft.city()).isNull();
    assertThat(draft.countryCode()).isEqualTo("FRA");
  }

  @Test
  void treatsAnUnresolvedLastSegmentAsCity() {
    // "Berlin" matches no country alias → it is the city, not the country (register §3.4's lone
    // ambiguous segment defaults to city).
    when(countryRepository.resolveAlias("Berlin")).thenReturn(Optional.empty());

    PayeeDraft draft = payeeService.parseCreateNew("Kiosk - Berlin");

    assertThat(draft.name()).isEqualTo("Kiosk");
    assertThat(draft.city()).isEqualTo("Berlin");
    assertThat(draft.countryCode()).isNull();
  }

  @Test
  void bareNameParsesToNameOnly() {
    PayeeDraft draft = payeeService.parseCreateNew("Spotify");

    assertThat(draft.name()).isEqualTo("Spotify");
    assertThat(draft.city()).isNull();
    assertThat(draft.countryCode()).isNull();
    // No segment past the name, so the country list is never consulted.
    verify(countryRepository, never()).resolveAlias(any());
  }

  @Test
  void splitsOnCommaAsWellAsDash() {
    when(countryRepository.resolveAlias("France")).thenReturn(Optional.of("FRA"));

    PayeeDraft draft = payeeService.parseCreateNew("Carrefour, Lyon, France");

    assertThat(draft.name()).isEqualTo("Carrefour");
    assertThat(draft.city()).isEqualTo("Lyon");
    assertThat(draft.countryCode()).isEqualTo("FRA");
  }

  @Test
  void rejectsBlankString() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> payeeService.parseCreateNew("   "))
        .withMessageContaining("name");
  }

  @Test
  void resolvePayeeUsesAnExistingPayeeVerbatim() {
    assertThat(payeeService.resolvePayee(42L, "ignored")).isEqualTo(42L);
    verify(payeeRepository, never()).insert(any(), any(), any());
  }

  @Test
  void resolvePayeeParsesAndInsertsCreateNewString() {
    when(countryRepository.resolveAlias("France")).thenReturn(Optional.of("FRA"));
    when(payeeRepository.findByAddress("Lidl", null, "FRA")).thenReturn(Optional.empty());
    when(payeeRepository.insert("Lidl", null, "FRA")).thenReturn(7L);

    assertThat(payeeService.resolvePayee(null, "Lidl - France")).isEqualTo(7L);
    verify(payeeRepository).insert("Lidl", null, "FRA");
  }

  @Test
  void resolvePayeeReusesAnExistingPayeeInsteadOfCreatingDuplicate() {
    // Re-picking an existing payee by text must not manufacture a new one each commit (register
    // §3.4) — otherwise the ghost suggestion breaks on fragmented history.
    when(countryRepository.resolveAlias("France")).thenReturn(Optional.of("FRA"));
    when(payeeRepository.findByAddress("Lidl", null, "FRA"))
        .thenReturn(Optional.of(new Payee(9L, "Lidl", null, "FRA", null)));

    assertThat(payeeService.resolvePayee(null, "Lidl - France")).isEqualTo(9L);
    verify(payeeRepository, never()).insert(any(), any(), any());
  }

  @Test
  void resolvePayeeReturnsNullForNoPayee() {
    // A transfer carries no payee (data-model §3.4): neither an existing id nor a create-new
    // string.
    assertThat(payeeService.resolvePayee(null, null)).isNull();
    assertThat(payeeService.resolvePayee(null, "  ")).isNull();
    verify(payeeRepository, never()).insert(any(), any(), any());
  }
}
