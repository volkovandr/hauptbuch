package volkovandr.hauptbuch.ledger.repository;

import java.math.BigDecimal;

/**
 * One pinned account's identity and its all-time native balance, as read by {@link
 * PinnedBalanceRepository#findPinnedBalances()} for the landing page's Balances panel (CONTEXT.md
 * "Balances panel", issue landing-page/01).
 *
 * @param accountId the account's surrogate PK — the register-link target on the panel
 * @param name the account's display name; the query orders rows by it
 * @param currencyCode ISO-4217 code of the account's single currency
 * @param hue the stored register hue (register §2.8) for the row's colour tick; nullable
 * @param balance Σ of every live posting to the account, its entire history, no date filter — zero
 *     when nothing has ever posted there
 */
public record PinnedBalance(
    long accountId, String name, String currencyCode, Integer hue, BigDecimal balance) {}
