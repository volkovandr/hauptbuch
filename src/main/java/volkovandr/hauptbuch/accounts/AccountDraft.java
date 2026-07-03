package volkovandr.hauptbuch.accounts;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A new account as submitted to {@link AccountService#openAccount} — before it is persisted, given
 * an id and a stored hue. Mirrors the ledger's draft convention ({@code TransactionDraft}): the
 * caller supplies intent, the service upholds the invariants.
 *
 * @param name display name; must not be blank
 * @param type {@code asset} or {@code liability} — the two types the accounts screen manages
 *     (income/expense are categories, stage 6b; equity is system plumbing)
 * @param parentId optional parent for a hierarchy; must be a same-type account with no postings
 *     (leaves-only, data-model §5)
 * @param currencyCode ISO-4217 code — any seeded currency (plan §1.2, multi-currency live)
 * @param openedAt the date the account opens; also the booking date of the opening balance
 * @param openingBalance signed starting balance in the account's currency, posted as a real
 *     balanced transaction against the per-currency Opening Balances leaf (data-model T-DM-4); null
 *     or zero = no opening transaction
 */
public record AccountDraft(
    String name,
    String type,
    Long parentId,
    String currencyCode,
    LocalDate openedAt,
    BigDecimal openingBalance) {}
