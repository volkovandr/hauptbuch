package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A booked opening balance of one account (data-model T-DM-4): the transaction id, the booking date
 * and the signed amount on the account's own leg, in its own currency. Read by the importer's
 * account map to reconcile against Money's staged opening balance (import.md §5.1; plan c3), and by
 * the import commit to void a superseded opening balance (plan f2).
 *
 * @param transactionId the opening-balance transaction's id — so a caller taking Money's opening
 *     balance instead can void this one (import.md §5.1)
 * @param date the opening balance's booking date
 * @param amount the signed amount on the account's leg ({@code +} for a positive asset balance)
 */
public record OpeningBalanceView(long transactionId, LocalDate date, BigDecimal amount) {}
