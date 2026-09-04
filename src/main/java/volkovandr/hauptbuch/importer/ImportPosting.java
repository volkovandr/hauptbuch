package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;

/**
 * A staged leg (import.md §11; plan b3). Its target is the still-unresolved Money string — a
 * category path (resolved via {@code import_category}) xor an account name (resolved via {@code
 * import_account}); the funding leg names the file's own account (§7).
 *
 * <p><strong>Sign.</strong> Amounts are stored in Hauptbuch's convention ({@code +} = debit, {@code
 * −} = credit; CLAUDE.md §4), not Money's: the funding leg carries the transaction total (the sum
 * of the line amounts, which equals Money's {@code T}; an outflow from an asset is already negative
 * in both conventions), and every category / transfer leg carries the negation of its Money {@code
 * $}/{@code T} amount. The legs of one staged transaction therefore sum to zero by construction.
 * Currency is still unknown (QIF names none, §5.1); it is fixed by the mapped account at commit.
 *
 * @param importPostingId surrogate PK; null for a not-yet-persisted row
 * @param importTransactionId the staged transaction this leg belongs to
 * @param amount the signed amount in the mapped account's (not-yet-known) currency
 * @param note the split {@code E} memo, or null
 * @param moneyCategoryPath the unresolved Money category path, or null when this is a transfer /
 *     funding leg
 * @param moneyAccountName the unresolved Money account name, or null when this is a category leg
 * @param className the {@code /Class} suffix — a tag at commit (§8), or null
 * @param mirrorPairId the other sighting of a transfer once matched within staging (§6.1), else
 *     null; set by slice e
 * @param counterAmount for a resolved <em>cross-currency</em> transfer leg (§6.2; plan e2a), the
 *     leg's amount in the target account's currency — the mirror sighting's funding-leg total, same
 *     sign as {@code amount}; null on every other leg and on an unresolved park. {@code
 *     base_amount} is still not fixed here — that is e3
 * @param funding true for the one synthesised leg on the file's own account that carries the record
 *     total (§7) — the figure ticked against Money's balance (§9.4); false for every category leg
 *     and every transfer leg, including an opening-balance self-transfer leg that names the same
 *     account
 */
public record ImportPosting(
    Long importPostingId,
    Long importTransactionId,
    BigDecimal amount,
    String note,
    String moneyCategoryPath,
    String moneyAccountName,
    String className,
    Long mirrorPairId,
    BigDecimal counterAmount,
    boolean funding) {}
