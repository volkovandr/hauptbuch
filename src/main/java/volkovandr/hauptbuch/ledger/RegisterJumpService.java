package volkovandr.hauptbuch.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * The register's {@code selected=} jump (register §7, plan stage 9g) — what a committed receipt's
 * "Edit transaction" and a Report's drill-down list (reporting.md §12's handoff) land on. It
 * derives the filter from the <em>transaction</em> rather than reusing the last-used one, so the
 * row it lands on is guaranteed visible: the transaction's leading own account is the viewed set
 * and its date is the range's lower bound, left open-ended upward so the row keeps its
 * running-balance context below it.
 *
 * <p>The leading own leg is the credited one, the biggest first — the account the money left: a
 * receipt's paying account (so a receipt that also carries a transfer line, cashback into savings,
 * §13.4, lands on the paying account's thread rather than opening both), a transfer's source. A
 * transaction with no own leg at all (a category-to-category correction) opens every account from
 * its date, since there is no one thread to pick.
 *
 * <p>Its own service rather than another method on {@link RegisterService}: this is one small,
 * self-contained derivation with nothing in common with assembling the screen.
 */
@Service
public class RegisterJumpService {

  private final RegisterRepository registerRepository;
  private final TransactionRepository transactionRepository;

  RegisterJumpService(
      RegisterRepository registerRepository, TransactionRepository transactionRepository) {
    this.registerRepository = registerRepository;
    this.transactionRepository = transactionRepository;
  }

  /**
   * The filter that guarantees {@code transactionId} is on screen.
   *
   * @return the derived filter, or empty when the transaction is voided or unknown — the caller
   *     then falls back to the ordinary default view
   */
  public Optional<RegisterFilter> filterForTransaction(long transactionId) {
    List<RegisterOwnLeg> legs = registerRepository.findOwnLegs(transactionId);
    // The jump carries at most one explicit account id, so it lands on the All tab (issue
    // transaction-register-ui/22 — an inbound link with explicit accounts and no picker opens on
    // All); with none, All's whole membership is the view.
    if (!legs.isEmpty()) {
      RegisterOwnLeg leading = legs.get(0);
      return Optional.of(
          new RegisterFilter(
              List.of(leading.accountId()), RegisterPicker.ALL, leading.date(), null, null));
    }
    return transactionRepository
        .findById(transactionId)
        .filter(transaction -> transaction.deletedAt() == null)
        .map(
            transaction ->
                new RegisterFilter(List.of(), RegisterPicker.ALL, transaction.date(), null, null));
  }
}
