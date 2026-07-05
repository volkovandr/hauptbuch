package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;

/**
 * A sibling leg of a register row's transaction — the raw material for the Category cell (register
 * §2.6). For a transaction on screen, its counterpart legs are every leg <em>other than</em> the
 * viewed one: the income/expense legs shown as category names, and another of your own accounts
 * shown as {@code ⇄ Account} (a transfer). The service turns a transaction's counterpart legs into
 * the summarised cell ("biggest wins · +n"); the SQL only fetches them.
 *
 * <p>Per-person debt legs (asset accounts with an owner) get their arrow-chip treatment at stage 8;
 * at 7a a debt leg simply renders like any other account leg.
 *
 * @param transactionId the transaction this leg belongs to (to group by on assembly)
 * @param accountId the leg's account
 * @param accountName the leg's account display name (the category name, or the transfer target)
 * @param accountType {@code income | expense | asset | liability | equity} — decides category vs
 *     transfer, and (via sign) income vs expense colouring
 * @param amount the signed native amount of this leg (magnitude ranks the "biggest wins" summary)
 */
public record RegisterCounterpartLeg(
    long transactionId,
    long accountId,
    String accountName,
    String accountType,
    BigDecimal amount) {}
