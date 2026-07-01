package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.repository.CurrencyRepository;
import volkovandr.hauptbuch.ledger.repository.SettingsRepository;

/**
 * Unit tier (plan §1.5): the write-once base-currency guard (data-model §3.8). The engine relies on
 * this guard to keep every frozen {@code baseAmount} interpretable, so it is enforced here at the
 * application layer rather than left to a DB trigger.
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

  private static final String CHF = "CHF";
  private static final String EUR = "EUR";

  @Mock private SettingsRepository settingsRepository;
  @Mock private CurrencyRepository currencyRepository;

  @Test
  void setsBaseCurrencyOnFreshBook() {
    when(settingsRepository.load()).thenReturn(new Settings(null, null));
    SettingsService service = new SettingsService(settingsRepository, currencyRepository);

    service.setBaseCurrency(EUR);

    verify(settingsRepository).updateBaseCurrency(EUR);
  }

  @Test
  void refusesToOverwriteAlreadySetBaseCurrency() {
    when(settingsRepository.load()).thenReturn(new Settings(EUR, null));
    SettingsService service = new SettingsService(settingsRepository, currencyRepository);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.setBaseCurrency(CHF))
        .withMessageContaining("write-once");

    verify(settingsRepository, never()).updateBaseCurrency(CHF);
  }

  @Test
  void baseCurrencyIsEmptyOnFreshBook() {
    when(settingsRepository.load()).thenReturn(new Settings(null, null));
    SettingsService service = new SettingsService(settingsRepository, currencyRepository);

    assertThat(service.baseCurrency()).isEmpty();
  }

  @Test
  void baseCurrencyIsPresentOnceSet() {
    when(settingsRepository.load()).thenReturn(new Settings(CHF, "Andrey"));
    SettingsService service = new SettingsService(settingsRepository, currencyRepository);

    assertThat(service.baseCurrency()).contains(CHF);
  }

  @Test
  void setsDisplayName() {
    SettingsService service = new SettingsService(settingsRepository, currencyRepository);

    service.setDisplayName("Andrey");

    verify(settingsRepository).updateDisplayName("Andrey");
  }

  @Test
  void offersSeededCurrenciesAsChoices() {
    List<Currency> seeded =
        List.of(new Currency(CHF, 2, "CHF", "Swiss Franc"), new Currency(EUR, 2, "€", "Euro"));
    when(currencyRepository.findAll()).thenReturn(seeded);
    SettingsService service = new SettingsService(settingsRepository, currencyRepository);

    assertThat(service.availableCurrencies()).isEqualTo(seeded);
  }
}
