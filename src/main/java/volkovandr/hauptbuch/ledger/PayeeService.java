package volkovandr.hauptbuch.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.ledger.repository.CountryRepository;
import volkovandr.hauptbuch.ledger.repository.PayeeRepository;

/**
 * Payee reads and create-new for the entry dock (register §3.4, plan stage 7b). A payee is true
 * reference data, so this is thin CRUD-with-parsing over {@link PayeeRepository}, not an
 * invariant-upholding domain operation (CLAUDE.md §1.7) — which is why it lives in {@code ledger}
 * (the payee's owning module) rather than {@code operations}.
 *
 * <p>The one piece of real logic is {@link #parseCreateNew}: splitting a typed {@code Name - City -
 * Country} string into a {@link PayeeDraft}, using the seeded country aliases to tell the country
 * segment from a city. It is pure over its inputs (the country resolver is injected), unit-tested
 * without a container.
 */
@Service
public class PayeeService {

  /** Segments are split on " - " or "," (register §3.4). */
  private static final String SEGMENT_SEPARATORS = "\\s*[-,]\\s*";

  private final PayeeRepository payeeRepository;
  private final CountryRepository countryRepository;

  PayeeService(PayeeRepository payeeRepository, CountryRepository countryRepository) {
    this.payeeRepository = payeeRepository;
    this.countryRepository = countryRepository;
  }

  /**
   * The existing payee a dock field's text names, or empty if it is a not-yet-created payee — the
   * lookup the ghost suggestion (register §3.9) keys on. The text is parsed the same way the commit
   * parses it ({@code Name - City - Country}) and matched on name+city+country, so a picked
   * datalist value resolves to the same payee the commit would reuse.
   */
  public Optional<Payee> findExisting(String payeeText) {
    if (payeeText == null || payeeText.isBlank()) {
      return Optional.empty();
    }
    PayeeDraft draft = parseCreateNew(payeeText);
    return payeeRepository.findByAddress(draft.name(), draft.city(), draft.countryCode());
  }

  /**
   * The {@code Name - City - Country} entry value for an existing payee (register §3.4) — the same
   * string the dock's datalist offers and the create-new parser round-trips, used to pre-fill the
   * payee input when a transaction is loaded into the dock's edit mode. Empty if the payee does not
   * exist (or is deleted, absent from the options).
   */
  public Optional<String> entryValueFor(long payeeId) {
    return payeeRepository.findFilterOptions().stream()
        .filter(o -> o.payeeId() == payeeId)
        .map(PayeeRepository.PayeeOption::entryValue)
        .findFirst();
  }

  /**
   * Parse a create-new payee string into a {@link PayeeDraft} (register §3.4). The typed string is
   * split on {@code " - "} / {@code ","}:
   *
   * <ul>
   *   <li>the <strong>first</strong> segment is the name;
   *   <li>the <strong>last</strong> segment is the country <em>iff</em> it matches a seeded country
   *       alias — otherwise it is treated as a city (a lone ambiguous segment defaults to city, the
   *       mini-form lets the user move it);
   *   <li>any <strong>middle</strong> segment is the city.
   * </ul>
   *
   * <p>Worked: {@code Lidl - France} → name {@code Lidl}, no city, country {@code FRA}. {@code Rewe
   * - Dortmund - Germany} → name {@code Rewe}, city {@code Dortmund}, country {@code DEU}. {@code
   * Kiosk - Berlin} → name {@code Kiosk}, city {@code Berlin} (Berlin matches no country), no
   * country.
   *
   * @throws IllegalArgumentException if the string has no non-blank name segment
   */
  public PayeeDraft parseCreateNew(String typed) {
    if (typed == null || typed.isBlank()) {
      throw new IllegalArgumentException("A payee needs a name");
    }
    String[] rawSegments = typed.trim().split(SEGMENT_SEPARATORS);
    List<String> segments =
        List.of(rawSegments).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    if (segments.isEmpty()) {
      throw new IllegalArgumentException("A payee needs a name");
    }

    String name = segments.get(0);
    if (segments.size() == 1) {
      return new PayeeDraft(name, null, null);
    }

    // The last segment is a country only if it resolves; otherwise it is a city.
    String last = segments.get(segments.size() - 1);
    Optional<String> country = countryRepository.resolveAlias(last);

    if (country.isPresent()) {
      // Everything between the name and the country segment is the city (register §3.4).
      String city = segments.size() > 2 ? segments.get(1) : null;
      return new PayeeDraft(name, city, country.get());
    }
    // No country matched: the last non-name segment is the city.
    return new PayeeDraft(name, segments.get(1), null);
  }

  /**
   * Resolve the dock's payee choice to a payee id. An explicit existing id is used as-is; otherwise
   * the typed text is parsed and <strong>reused if a payee with that exact name/city/country
   * already exists</strong> — only a genuinely new payee is inserted. Reuse is essential: without
   * it, every commit made a fresh duplicate payee, fragmenting history and breaking the ghost
   * suggestion (register §3.4/§3.9). Returns {@code null} for a transaction with no payee
   * (transfers — data-model §3.4).
   *
   * @param existingPayeeId the picked existing payee, or null
   * @param payeeText the payee text when no existing id was given (a picked name or a create-new
   *     string), or null/blank for no payee
   */
  @Transactional
  public Long resolvePayee(Long existingPayeeId, String payeeText) {
    if (existingPayeeId != null) {
      return existingPayeeId;
    }
    if (payeeText == null || payeeText.isBlank()) {
      return null;
    }
    PayeeDraft draft = parseCreateNew(payeeText);
    return payeeRepository
        .findByAddress(draft.name(), draft.city(), draft.countryCode())
        .map(Payee::payeeId)
        .orElseGet(() -> payeeRepository.insert(draft.name(), draft.city(), draft.countryCode()));
  }
}
