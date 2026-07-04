package volkovandr.hauptbuch.ledger;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.repository.CurrencyRepository;

/**
 * The book's currency list (data-model §3.1) — {@code ledger} owns the {@code currency} table, so
 * this is its public read/write API. Read is what every currency picker offers; write is the
 * currency insert half of the stage-6d {@code createCurrency} operation.
 *
 * <p>The full provisioning (insert the currency <em>and</em> its per-currency system leaves) is the
 * {@code operations} module's {@code createCurrency} — it composes this insert with {@code
 * accounts}' leaf creation, because provisioning spans two modules' tables. This service only owns
 * the currency row itself.
 */
@Service
public class CurrencyService {

  private final CurrencyRepository currencyRepository;

  CurrencyService(CurrencyRepository currencyRepository) {
    this.currencyRepository = currencyRepository;
  }

  /** All currencies the book knows, ordered by ISO code — what a currency picker lists. */
  public List<Currency> findAll() {
    return currencyRepository.findAll();
  }

  /** Whether a currency with this ISO code already exists (its natural key). */
  public boolean exists(String code) {
    return currencyRepository.existsByCode(code);
  }

  /**
   * Insert a currency row after validating and normalising its fields. The caller ({@code
   * operations}' {@code createCurrency}) is responsible for provisioning the currency's system
   * leaves in the same transaction; this only writes the {@code currency} row.
   *
   * @throws IllegalArgumentException if the code is not a 3-letter ISO code, the name is blank, the
   *     minor units are out of range, or the currency already exists
   */
  @Transactional
  public Currency insert(String code, int minorUnits, String symbol, String name) {
    String normalisedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    validate(normalisedCode, minorUnits, name);
    String normalisedSymbol = symbol == null || symbol.isBlank() ? null : symbol.trim();
    Currency currency = new Currency(normalisedCode, minorUnits, normalisedSymbol, name.trim());
    currencyRepository.insert(currency);
    return currency;
  }

  /** The currency-field rules: a 3-letter ISO code, a name, in-range minor units, and no clash. */
  private void validate(String normalisedCode, int minorUnits, String name) {
    if (!normalisedCode.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException(
          "Currency code must be a 3-letter ISO-4217 code, not '" + normalisedCode + "'");
    }
    if (isBlank(name)) {
      throw new IllegalArgumentException("A currency needs a name");
    }
    if (outOfRange(minorUnits)) {
      throw new IllegalArgumentException("Minor units must be between 0 and 4, not " + minorUnits);
    }
    if (currencyRepository.existsByCode(normalisedCode)) {
      throw new IllegalArgumentException("Currency '" + normalisedCode + "' already exists");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean outOfRange(int minorUnits) {
    return minorUnits < 0 || minorUnits > 4;
  }
}
