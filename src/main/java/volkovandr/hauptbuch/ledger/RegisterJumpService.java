package volkovandr.hauptbuch.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;

/**
 * The register's {@code selected=} jump (register §7, plan stage 9g) — what a committed receipt's
 * "Edit transaction" lands on. It derives the filter from the <em>transaction</em> rather than
 * reusing the last-used one, so the row it lands on is guaranteed visible: the transaction's
 * <em>funding</em> account is the viewed set and its date is the range's lower bound, left
 * open-ended upward so the row keeps its running-balance context below it.
 *
 * <p>The funding account is the biggest own leg — the account the money actually moved through,
 * which for a receipt is the one that paid. A receipt that also carries a transfer line (cashback
 * into savings, §13.4) therefore lands on the paying account's thread rather than opening both.
 *
 * <p>Its own service rather than another method on {@link RegisterService}: this is one small,
 * self-contained derivation with nothing in common with assembling the screen.
 */
@Service
public class RegisterJumpService {

  private final RegisterRepository registerRepository;

  RegisterJumpService(RegisterRepository registerRepository) {
    this.registerRepository = registerRepository;
  }

  /**
   * The filter that guarantees {@code transactionId} is on screen.
   *
   * @return the derived filter, or empty when the transaction is voided or unknown — the caller
   *     then falls back to the ordinary default view
   */
  public Optional<RegisterFilter> filterForTransaction(long transactionId) {
    List<RegisterOwnLeg> legs = registerRepository.findOwnLegs(transactionId);
    if (legs.isEmpty()) {
      return Optional.empty();
    }
    // The query orders by descending magnitude, so the first leg is the funding one.
    RegisterOwnLeg funding = legs.get(0);
    return Optional.of(
        new RegisterFilter(List.of(funding.accountId()), funding.date(), null, null));
  }
}
