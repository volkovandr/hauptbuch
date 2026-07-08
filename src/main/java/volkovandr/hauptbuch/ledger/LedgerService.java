package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * The double-entry engine's domain-operations layer (plan §1.4). <em>Not</em> row CRUD: recording a
 * transaction creates balanced postings and upholds the model's invariants (data-model §8) — it is
 * the only sanctioned way to write the ledger.
 *
 * <p>Three operations:
 *
 * <ul>
 *   <li>{@link #recordTransaction} — validate the legs balance, then insert the transaction and its
 *       postings atomically;
 *   <li>{@link #voidTransaction} — reversible soft delete (data-model §3.5);
 *   <li>{@link #editTransaction} — re-thread: replace the legs with a freshly-validated set.
 * </ul>
 *
 * <p>Multi-currency is live here (plan §1.2). A single-currency transaction must sum to zero in
 * native amounts. A cross-currency transaction must carry a frozen {@code baseAmount} on every leg
 * and sum to zero in base; if the supplied base amounts do not balance, the residual is a genuine
 * conversion gain/loss and is booked to the base-currency {@code FX gain/loss} leaf (data-model
 * §6.3/§6.4) — the residual that makes a non-par conversion balance.
 */
@Service
public class LedgerService {

  /** The seeded system parent whose per-currency leaf receives a conversion residual (§6.3). */
  static final String FX_GAIN_LOSS_PARENT = "FX gain/loss";

  /** A balanced transaction has at least two legs (one debit, one credit). */
  private static final int MIN_LEGS = 2;

  /** A transaction touching exactly one currency is single-currency; more is cross-currency. */
  private static final int SINGLE_CURRENCY = 1;

  private final SettingsService settingsService;
  private final AccountService accountService;
  private final TransactionRepository transactionRepository;

  LedgerService(
      SettingsService settingsService,
      AccountService accountService,
      TransactionRepository transactionRepository) {
    this.settingsService = settingsService;
    this.accountService = accountService;
    this.transactionRepository = transactionRepository;
  }

  /**
   * Record a balanced transaction. Validates the legs against the sum-to-zero, leaves-only, and
   * currency-consistency invariants (data-model §8) before any insert; persists the transaction and
   * its legs in one DB transaction.
   *
   * @return the new transaction's id
   * @throws IllegalStateException if the book's base currency is not yet set (data-model §3.8)
   * @throws UnbalancedTransactionException if the legs violate a posting invariant
   */
  @Transactional
  public long recordTransaction(TransactionDraft draft) {
    String baseCurrency = requireBaseCurrency();
    List<PostingDraft> legs = balancedLegs(draft.postings(), baseCurrency);

    long transactionId =
        transactionRepository.insertTransaction(
            new Transaction(
                null,
                draft.date(),
                draft.payeeId(),
                draft.note(),
                draft.lifecycle(),
                null,
                null,
                null));
    insertLegs(transactionId, legs);
    return transactionId;
  }

  /**
   * A live (not soft-deleted) transaction by id, for loading it into the entry dock's edit mode
   * (register §3.1). A read the dock needs before it can re-thread; pairs with {@link
   * #findPostings}. Returns empty for a missing or voided transaction.
   */
  public Optional<Transaction> findTransaction(long transactionId) {
    return transactionRepository.findById(transactionId).filter(t -> t.deletedAt() == null);
  }

  /**
   * The legs of a transaction, in posting-id order — the other half of an edit-mode load (register
   * §3.1). The dock classifies them into the funding (own-account) leg and the category legs.
   */
  public List<Posting> findPostings(long transactionId) {
    return transactionRepository.findPostings(transactionId);
  }

  /** Reversibly soft-delete a transaction and (by the join) its postings (data-model §3.5). */
  @Transactional
  public void voidTransaction(long transactionId) {
    int affected = transactionRepository.softDelete(transactionId);
    if (affected == 0) {
      throw new IllegalArgumentException(
          "No live transaction with id " + transactionId + " to void");
    }
  }

  /**
   * Edit a transaction by re-threading it: update the header and replace its legs with a
   * freshly-validated set. The same balance/leaves-only rules as {@link #recordTransaction} apply.
   *
   * @throws IllegalArgumentException if the transaction does not exist or is not live
   */
  @Transactional
  public void editTransaction(long transactionId, TransactionDraft draft) {
    String baseCurrency = requireBaseCurrency();
    Transaction existing =
        transactionRepository
            .findById(transactionId)
            .filter(t -> t.deletedAt() == null)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No live transaction with id " + transactionId + " to edit"));

    List<PostingDraft> legs = balancedLegs(draft.postings(), baseCurrency);

    transactionRepository.updateHeader(
        new Transaction(
            existing.transactionId(),
            draft.date(),
            draft.payeeId(),
            draft.note(),
            draft.lifecycle(),
            existing.createdAt(),
            null,
            null));
    transactionRepository.deletePostings(transactionId);
    insertLegs(transactionId, legs);
  }

  private String requireBaseCurrency() {
    return settingsService
        .baseCurrency()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Base currency is not set; no transaction can be recorded until first-run "
                        + "setup sets it (data-model §3.8)"));
  }

  /**
   * Validate the submitted legs and return the legs to persist — the same legs for a balanced
   * single-currency or already-balanced cross-currency transaction, plus an FX gain/loss residual
   * leg when a cross-currency conversion does not balance.
   */
  private List<PostingDraft> balancedLegs(List<PostingDraft> postings, String baseCurrency) {
    if (postings.size() < MIN_LEGS) {
      throw new UnbalancedTransactionException(
          "A transaction needs at least two postings to balance");
    }

    Set<Long> parentAccountIds = new HashSet<>(accountService.findParentAccountIds());
    Set<String> currencies = new HashSet<>();
    for (PostingDraft leg : postings) {
      Account account = requireLeafAccount(leg.accountId(), parentAccountIds);
      currencies.add(account.currencyCode());
    }

    if (currencies.size() == SINGLE_CURRENCY) {
      return validatedSingleCurrency(postings);
    }
    return validatedCrossCurrency(postings, baseCurrency);
  }

  /** Single-currency: the native amounts must sum to zero exactly (data-model §8, branch 1). */
  private List<PostingDraft> validatedSingleCurrency(List<PostingDraft> postings) {
    BigDecimal nativeSum = BigDecimal.ZERO;
    for (PostingDraft leg : postings) {
      requireAmount(leg);
      nativeSum = nativeSum.add(leg.amount());
    }
    if (nativeSum.signum() != 0) {
      throw new UnbalancedTransactionException(
          "Single-currency transaction does not sum to zero: native sum is " + nativeSum);
    }
    return List.copyOf(postings);
  }

  /**
   * Cross-currency: every leg must carry a frozen {@code baseAmount}; the base amounts must sum to
   * zero, or the residual is booked to the base-currency FX gain/loss leaf (data-model §6.3/§6.4).
   */
  private List<PostingDraft> validatedCrossCurrency(
      List<PostingDraft> postings, String baseCurrency) {
    BigDecimal baseSum = BigDecimal.ZERO;
    for (PostingDraft leg : postings) {
      requireAmount(leg);
      if (leg.baseAmount() == null) {
        throw new UnbalancedTransactionException(
            "Cross-currency leg on account "
                + leg.accountId()
                + " is missing its base_amount "
                + "(data-model §6.4)");
      }
      baseSum = baseSum.add(leg.baseAmount());
    }

    if (baseSum.signum() == 0) {
      return List.copyOf(postings);
    }

    // A non-par conversion: the residual that makes it balance is a real FX gain/loss (§6.3).
    Account fxLeaf =
        accountService
            .findLeafUnderParentNamed(FX_GAIN_LOSS_PARENT, baseCurrency)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No FX gain/loss leaf for base currency " + baseCurrency));
    BigDecimal residual = baseSum.negate();
    List<PostingDraft> withResidual = new ArrayList<>(postings);
    // The FX leaf is in the base currency, so its native amount equals its base amount.
    withResidual.add(PostingDraft.ofCrossCurrency(fxLeaf.accountId(), residual, residual));
    return List.copyOf(withResidual);
  }

  private void insertLegs(long transactionId, List<PostingDraft> legs) {
    legs.stream()
        .map(leg -> toPosting(transactionId, leg))
        .forEach(transactionRepository::insertPosting);
  }

  private static Posting toPosting(long transactionId, PostingDraft leg) {
    return new Posting(
        null,
        transactionId,
        leg.accountId(),
        leg.amount(),
        leg.baseAmount(),
        leg.reconciliation() == null ? "unreconciled" : leg.reconciliation(),
        leg.note());
  }

  private Account requireLeafAccount(long accountId, Set<Long> parentAccountIds) {
    Account account =
        accountService
            .findById(accountId)
            .orElseThrow(
                () -> new UnbalancedTransactionException("No account with id " + accountId));
    if (parentAccountIds.contains(accountId)) {
      throw new UnbalancedTransactionException(
          "Posting to non-leaf account "
              + accountId
              + " is forbidden (leaves-only, data-model §5)");
    }
    return account;
  }

  private static void requireAmount(PostingDraft leg) {
    if (leg.amount() == null) {
      throw new UnbalancedTransactionException(
          "Posting to account " + leg.accountId() + " has no amount");
    }
  }
}
