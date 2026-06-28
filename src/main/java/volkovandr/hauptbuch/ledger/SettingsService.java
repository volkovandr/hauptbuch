package volkovandr.hauptbuch.ledger;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.repository.SettingsRepository;

/**
 * Read/write access to the book's global settings, with the write-once base-currency guard
 * (data-model §3.8). This is the engine's gatekeeper for the "base currency is set" precondition:
 * {@link LedgerService} consults {@link #baseCurrency()} before recording any transaction.
 *
 * <p>The base-currency UI is stage 5; this service is born at stage 3 because the engine depends on
 * it. Write-once is enforced here (the application layer), not by a DB trigger — the same stance
 * the model takes on the sum-to-zero invariant (data-model T-DM-2).
 */
@Service
public class SettingsService {

  private final SettingsRepository settingsRepository;

  SettingsService(SettingsRepository settingsRepository) {
    this.settingsRepository = settingsRepository;
  }

  /** The full settings row (base currency + display name). */
  public Settings get() {
    return settingsRepository.load();
  }

  /** The base currency, or empty on a fresh book where it has not yet been set. */
  public Optional<String> baseCurrency() {
    return Optional.ofNullable(settingsRepository.load().baseCurrency());
  }

  /**
   * Set the base currency on first run. Write-once: refuses to overwrite a base currency that is
   * already set, because every frozen {@code baseAmount} and every booked conversion is denominated
   * in it (data-model §3.8).
   *
   * @throws IllegalStateException if the base currency is already set
   */
  @Transactional
  public void setBaseCurrency(String currencyCode) {
    if (settingsRepository.load().baseCurrency() != null) {
      throw new IllegalStateException(
          "Base currency is write-once and is already set; it cannot be changed.");
    }
    settingsRepository.updateBaseCurrency(currencyCode);
  }

  /** Set the freely-editable display name backing the greeting. */
  @Transactional
  public void setDisplayName(String displayName) {
    settingsRepository.updateDisplayName(displayName);
  }
}
