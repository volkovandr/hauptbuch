package volkovandr.hauptbuch.operations;

import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.SettingsService;

/**
 * The <em>transaction currency</em> — the currency of every leg that is not a real account
 * (register §3.5, plan stage 8b.1).
 *
 * <p>With a real funding account this is just that account's currency, and the currency selector is
 * an override that declares the transaction cross-currency; nothing here is needed. With
 * <em>no</em> real account — a person funding a category (register §2.6 pattern 3), or a
 * person-to-person debt transfer — the selector instead sets <em>every</em> leg, the transaction is
 * single-currency, and this is the only currency source there is. That is precisely what lets a
 * brand-new person be entered in the Account field: provisioning finally has a currency to create
 * the leaf in.
 *
 * <p>Shared by {@link DockCommitService} (which provisions the leaf) and {@link
 * DockAmountFieldsService} (which decides what the amount fields and the currency picker show), so
 * the default the user is shown and the currency actually booked can never disagree.
 */
@Service
class TransactionCurrencyResolver {

  private final PersonService personService;
  private final SettingsService settingsService;

  TransactionCurrencyResolver(PersonService personService, SettingsService settingsService) {
    this.personService = personService;
    this.settingsService = settingsService;
  }

  /**
   * The currency for a transaction whose funding leg is a person: the selector's explicit choice
   * when there is one, else the person's existing debt currency when that is unambiguous, else the
   * book's base currency.
   *
   * @param personName the funding person
   * @param override the currency selector's value; {@code null}/blank when untouched
   * @return the resolved currency, or {@code null} when the book has no base currency yet and
   *     nothing else supplied one (the caller decides whether that is an error or just an empty
   *     field)
   */
  String forFundingPerson(String personName, String override) {
    if (override != null && !override.isBlank()) {
      return override;
    }
    return personService
        .soleDebtCurrency(personName)
        .or(settingsService::baseCurrency)
        .orElse(null);
  }
}
