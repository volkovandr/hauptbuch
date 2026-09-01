package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;

/**
 * A row of the account map (import.md §5.1, §5.4), accumulated across every file in the campaign.
 * Plan b3 creates it <strong>unmapped</strong> — every column past {@code moneyAccountName} null or
 * at its default — as each file's referenced account names are folded in; slice c resolves the
 * target, the currency, {@code expectFile} and the opening-balance reconciliation.
 *
 * @param importAccountId surrogate PK; null for a not-yet-persisted row
 * @param importSessionId the campaign this map belongs to
 * @param moneyAccountName the Money account name — the map key, unique per session
 * @param accountId the existing Hauptbuch account this maps to (c1), or null while unmapped
 * @param personId the person this maps to (c2), or null; never set alongside {@code accountId}
 * @param targetCurrencyCode the currency chosen for a new account or a person leaf (c1/c2), or null
 * @param expectFile whether this account's own export is still awaited — the gate's only escape
 *     hatch (§6.4); true until slice c clears it
 * @param openingBalanceChoice the c3 reconciliation outcome ({@code keep_hauptbuch} / {@code
 *     take_money} / {@code override}), or null until reconciled
 * @param openingBalanceAmount the amount for an {@code override} choice, or null
 */
public record ImportAccount(
    Long importAccountId,
    Long importSessionId,
    String moneyAccountName,
    Long accountId,
    Long personId,
    String targetCurrencyCode,
    boolean expectFile,
    String openingBalanceChoice,
    BigDecimal openingBalanceAmount) {}
