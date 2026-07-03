package volkovandr.hauptbuch.accounts.repository;

/**
 * A seeded currency as offered in the account form's currency select — just the code and the
 * human-readable name, nothing more. A read-only projection of the {@code currency} table, which
 * {@code ledger} owns; duplicating the full {@code Currency} type here would split that concept.
 * Stage 6c replaces this select with the ledger-owned currency-picker fragment (plan stage 6c), at
 * which point this projection goes away.
 *
 * @param code ISO-4217 code, e.g. {@code EUR}
 * @param name human-readable name, e.g. {@code Euro}
 */
public record CurrencyOption(String code, String name) {}
