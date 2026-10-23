package volkovandr.hauptbuch.receipts;

/**
 * A {@link ConfirmToast} still on screen, as the shared toast fragment renders it: the CSS fade-out
 * runs for {@code durationSeconds} and starts {@code elapsedSeconds} in, so a page reload does not
 * restart it.
 *
 * @param receiptId the confirmed receipt
 * @param date the booking date, {@code dd.MM.yyyy}
 * @param payee the payee as typed; blank when none
 * @param amount the receipt total as shown
 * @param durationSeconds the configured toast lifetime
 * @param elapsedSeconds how long ago the receipt was confirmed
 */
public record ConfirmToastView(
    long receiptId,
    String date,
    String payee,
    String amount,
    int durationSeconds,
    double elapsedSeconds) {}
