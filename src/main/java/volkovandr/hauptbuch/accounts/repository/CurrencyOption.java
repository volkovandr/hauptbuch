package volkovandr.hauptbuch.accounts.repository;

/**
 * A currency as offered in the account form's currency select — just the code and the
 * human-readable name, nothing more. A read-only projection of the {@code currency} table, which
 * {@code ledger} owns; duplicating the full {@code Currency} type here would split that concept.
 *
 * <p>Stage 6d's shared currency-picker fragment is duck-typed on {@code code()}/{@code name()}, so
 * the accounts screen feeds it these projections while the settings screen feeds {@code ledger}'s
 * {@code Currency} — the projection <em>stays</em>, because {@code accounts} cannot depend on
 * {@code ledger} (that would close the sanctioned {@code ledger → accounts} direction into a
 * cycle).
 *
 * @param code ISO-4217 code, e.g. {@code EUR}
 * @param name human-readable name, e.g. {@code Euro}
 */
public record CurrencyOption(String code, String name) {}
