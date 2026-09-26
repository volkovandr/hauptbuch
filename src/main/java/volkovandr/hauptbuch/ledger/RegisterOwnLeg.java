package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;

/**
 * One of a transaction's legs into your <em>own</em> accounts (asset/liability), with the
 * transaction's booking date — the raw material for the register's {@code selected=} jump (register
 * §7, plan stage 9g). The jump derives its filter from the transaction rather than reusing the
 * last-used one, so the row it lands on is guaranteed visible: these legs are the accounts to view,
 * and the date is the range's lower bound.
 *
 * <p>Own accounts only, because they are the ones the register threads. A transaction with no own
 * leg at all (a category-to-category correction) yields none, and the jump opens every account.
 *
 * @param date the transaction's booking date
 * @param accountId the own account this leg hits
 */
public record RegisterOwnLeg(LocalDate date, long accountId) {}
