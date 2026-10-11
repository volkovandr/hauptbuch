package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A posting on a statement's account that is matched to a different live statement of the same
 * account (statements.md §6.2): it explains the balance difference where the ledger date and that
 * statement's booking period fall on opposite sides of this statement's boundary.
 *
 * @param transactionDate the date the ledger gives the posting
 * @param amount the posting's signed amount
 * @param periodEnd the end of the statement it is matched to
 */
public record NeighbourMatch(LocalDate transactionDate, BigDecimal amount, LocalDate periodEnd) {}
