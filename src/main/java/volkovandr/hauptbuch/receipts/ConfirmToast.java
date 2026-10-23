package volkovandr.hauptbuch.receipts;

import java.time.Instant;

/**
 * One "Confirmed!" toast, kept in the HTTP session so it survives the redirect that follows a
 * Confirm (receipt issue 38). Carries only what the toast shows — date, payee, amount — never
 * ledger detail beyond that.
 *
 * @param receiptId the confirmed receipt, the target of the toast's edit link
 * @param date the booking date as the operator sees it ({@code dd.MM.yyyy})
 * @param payee the payee as typed; blank when the receipt had none
 * @param amount the receipt total, German-formatted, with the currency code unless it is the base
 * @param confirmedAt when the Confirm succeeded
 */
record ConfirmToast(
    long receiptId, String date, String payee, String amount, Instant confirmedAt) {}
