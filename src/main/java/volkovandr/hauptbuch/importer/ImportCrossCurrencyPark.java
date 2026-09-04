package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One still-parked cross-currency transfer leg (import.md §6.2, §6.5; plan e2b) — a row of the
 * review's cross-currency panel. Every row is a candidate for {@link
 * ImportCrossCurrencyParkService#manualMatch} against another row of the same list; a row whose
 * named account's {@code expect-file} is cleared (§6.4) is additionally eligible for {@link
 * ImportCrossCurrencyParkService#closeParkWithFarAmount}.
 *
 * @param importPostingId the parked transfer leg's posting id — the manual-match / hand-entered
 *     action's target
 * @param importTransactionId the staged transaction this leg belongs to
 * @param date the transaction date
 * @param nearMoneyAccountName the file's own Money account name — the near side, whose currency
 *     {@code amount} is stated in
 * @param farMoneyAccountName the Money account this leg transfers to — the far side, whose amount
 *     is still missing
 * @param amount the near-side signed amount, in the near account's currency
 * @param farExpectFile whether the far account is still awaiting its own export (§5.1) — false is
 *     the hand-entered path's gate (§6.4)
 */
public record ImportCrossCurrencyPark(
    long importPostingId,
    long importTransactionId,
    LocalDate date,
    String nearMoneyAccountName,
    String farMoneyAccountName,
    BigDecimal amount,
    boolean farExpectFile) {}
