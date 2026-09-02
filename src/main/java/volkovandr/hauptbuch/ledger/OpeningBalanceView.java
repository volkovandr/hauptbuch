package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A booked opening balance of one account (data-model T-DM-4): the booking date and the signed
 * amount on the account's own leg, in its own currency. Read by the importer's account map to
 * reconcile against Money's staged opening balance (import.md §5.1; plan c3).
 *
 * @param date the opening balance's booking date
 * @param amount the signed amount on the account's leg ({@code +} for a positive asset balance)
 */
public record OpeningBalanceView(LocalDate date, BigDecimal amount) {}
