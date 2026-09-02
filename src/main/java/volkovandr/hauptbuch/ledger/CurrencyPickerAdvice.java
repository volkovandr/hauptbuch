package volkovandr.hauptbuch.ledger;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Supplies {@code baseCurrencyCode} to every rendered view so the shared currency-picker fragment
 * ({@code fragments/currency-picker}) can default a fresh picker — one with no screen-specific
 * pre-selection — to the book's base currency. Most accounts are held in the base currency, so the
 * old "first option, alphabetically" default was almost always the wrong guess.
 *
 * <p>Global on purpose: the picker appears on screens across several modules (accounts, register,
 * receipts, import), and threading the attribute through each controller would be brittle and easy
 * to forget. It lives in {@code ledger} — the currency picker's owning module and the home of
 * {@link SettingsService}; {@code web} could not host it without a cycle, since feature modules
 * already depend on {@code web}.
 *
 * <p>{@code null} on a fresh book whose base currency is unset — the fragment then falls back to
 * the first option, which is also the right behaviour for the first-run base-currency screen.
 */
@ControllerAdvice
class CurrencyPickerAdvice {

  private final SettingsService settingsService;

  CurrencyPickerAdvice(SettingsService settingsService) {
    this.settingsService = settingsService;
  }

  @ModelAttribute("baseCurrencyCode")
  String baseCurrencyCode() {
    return settingsService.baseCurrency().orElse(null);
  }
}
