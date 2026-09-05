package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A same-currency transfer whose two sightings are <strong>both</strong> a split (import.md §6.1;
 * plan e4) — the one shape {@code ImportMirrorRepository}'s automatic matching cannot resolve:
 * excluding either sighting wholesale would also drop its unrelated category legs, so neither is
 * excluded and both stay {@code ready}. Left unresolved, both book their own transfer leg and the
 * transferred amount is double-counted at commit. A row of the review's issues list — informational
 * only, since nothing here derives or applies a fix; the owner resolves it by hand (e.g.
 * re-splitting in Money and re-exporting, or voiding the duplicate leg in the ledger after commit).
 *
 * @param transactionId one sighting's staged transaction
 * @param mirrorTransactionId the other sighting's staged transaction
 * @param date the transfer date
 * @param moneyAccountName the Money account {@code transactionId} was staged from
 * @param mirrorMoneyAccountName the Money account {@code mirrorTransactionId} was staged from
 * @param amount {@code transactionId}'s own signed transfer-leg amount
 */
public record ImportUnresolvedMirror(
    long transactionId,
    long mirrorTransactionId,
    LocalDate date,
    String moneyAccountName,
    String mirrorMoneyAccountName,
    BigDecimal amount) {}
