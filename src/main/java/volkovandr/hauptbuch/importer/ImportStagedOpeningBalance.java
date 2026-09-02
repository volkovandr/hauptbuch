package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Money's opening balance for one Money account, as staged (import.md §5.1; plan c3): Money exports
 * it as a single-line self-transfer with payee {@code Opening Balance}, which staging keeps as an
 * {@code import_transaction} flagged {@code opening_balance} whose funding leg carries the figure.
 * The account map reconciles this against the target Hauptbuch account's own opening balance.
 *
 * <p>One row per staged file that carries an opening-balance record; a rare account with two files
 * each carrying one yields two rows, and the map takes the earlier-dated ({@link
 * OpeningBalanceReconciliation}).
 *
 * @param moneyAccountName the Money account name — the map key (import.md §5.1)
 * @param date the opening balance's booking date
 * @param amount the funding-leg amount, in Hauptbuch's sign convention (§7)
 */
public record ImportStagedOpeningBalance(
    String moneyAccountName, LocalDate date, BigDecimal amount) {}
