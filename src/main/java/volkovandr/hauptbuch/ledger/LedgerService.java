package volkovandr.hauptbuch.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.repository.TagReadRepository;
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
 * and sum to zero in base; if the supplied base amounts do not balance, the transaction is
 * <em>rejected</em> (data-model §6.3, 2026-07-11). The engine never invents a residual leg — a
 * genuine conversion gain/loss is a manual {@code FX gain/loss} line the caller supplies.
 */
// CouplingBetweenObjects: this class sat at 19 of the threshold's 20 before it logged anything;
// naming SLF4J's Logger and LoggerFactory tips it over. Those are logging infrastructure, not two
// more domain collaborators, so the finding is not the coupling the rule exists to catch — but be
// clear about the cost: the rule reports at the type node, so there is no narrower scope than the
// class, and this annotation therefore also silences *genuine* coupling growth here from now on.
// It is the ledger engine, so that matters. The real fix is to split this class's batched read
// accessors (findPostings, tagsForTransaction, tagIdsForPosting, labelsForTagIds,
// voidedTransactionIds, datesForTransactions — added for other modules) away from the three write
// operations, which drops the count well clear of the threshold and removes the need for this
// annotation entirely. Tracked as ledger-engine/01; delete this suppression when that lands.
@SuppressWarnings("PMD.CouplingBetweenObjects")
@Service
public class LedgerService {

  private static final Logger LOG = LoggerFactory.getLogger(LedgerService.class);

  /** A balanced transaction has at least two legs (one debit, one credit). */
  private static final int MIN_LEGS = 2;

  /** A transaction touching exactly one currency is single-currency; more is cross-currency. */
  private static final int SINGLE_CURRENCY = 1;

  private final SettingsService settingsService;
  private final AccountService accountService;
  private final TransactionRepository transactionRepository;
  private final TagReadRepository tagReadRepository;

  LedgerService(
      SettingsService settingsService,
      AccountService accountService,
      TransactionRepository transactionRepository,
      TagReadRepository tagReadRepository) {
    this.settingsService = settingsService;
    this.accountService = accountService;
    this.transactionRepository = transactionRepository;
    this.tagReadRepository = tagReadRepository;
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
    BalancedLegs balanced = balancedLegs(draft.postings(), baseCurrency);

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
    insertLegs(transactionId, balanced.legs());
    LOG.debug("Transaction recorded: id={}, total={}", transactionId, balanced.debitTotal());
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
   * Which of {@code transactionIds} are voided — a batched sibling to {@link #findTransaction}'s
   * single-id liveness check (issue tracker #08), for a caller marking many transaction references
   * at once (e.g. a committed-receipt's list/grid render) without a query per row.
   */
  public Set<Long> voidedTransactionIds(Collection<Long> transactionIds) {
    return Set.copyOf(transactionRepository.findVoidedIds(List.copyOf(transactionIds)));
  }

  /**
   * The booking date of each transaction in {@code transactionIds}, live or soft-deleted — the
   * receipts register's transaction-date column (issue tracker #09), one batched lookup for a whole
   * list/grid render rather than a query per row.
   */
  public Map<Long, LocalDate> datesForTransactions(Collection<Long> transactionIds) {
    return transactionRepository.findDatesByIds(transactionIds);
  }

  /**
   * The legs of a transaction, in posting-id order — the other half of an edit-mode load (register
   * §3.1). The dock classifies them into the funding (own-account) leg and the category legs.
   */
  public List<Posting> findPostings(long transactionId) {
    return transactionRepository.findPostings(transactionId);
  }

  /**
   * The distinct tags a transaction's postings carry, with canonical labels (register §3.6) — the
   * other half of the dock's edit-mode load, so the chip field can re-render the pills the user
   * entered. Exposed for {@code operations}' {@code DockEditService}, which cannot reach {@code
   * ledger}'s repositories directly.
   */
  public List<TransactionTag> tagsForTransaction(long transactionId) {
    return tagReadRepository.tagsForTransaction(transactionId);
  }

  /**
   * The tag ids a single leg carries, in entry order (data-model §10.2) — the split panel's
   * edit-mode load (register §3.10, plan stage 7e.3), which reconstructs the funding leg's and each
   * category line's own tags separately (unlike {@link #tagsForTransaction}, which collapses the
   * whole transaction's tags to one set). Exposed for {@code operations}' split load, which cannot
   * reach {@code ledger}'s repositories directly.
   */
  public List<Long> tagIdsForPosting(long postingId) {
    return transactionRepository.findTagIdsByPosting(postingId);
  }

  /**
   * The canonical {@code Parent:Child} label of each given tag id, keyed by id (register §3.6, plan
   * stage 7e.3) — so the split panel can re-render its chip pills from the ids the form carries on
   * each round-trip. Exposed for {@code operations}' {@code SplitPanelAssembler}.
   */
  public Map<Long, String> labelsForTagIds(Collection<Long> tagIds) {
    return tagReadRepository.labelsForTagIds(tagIds);
  }

  /**
   * The account's own opening balance (data-model T-DM-4), or empty when it has none — the target
   * side of the importer's opening-balance reconciliation (import.md §5.1; plan c3). Resolved by
   * finding the account's per-currency {@code Opening Balances} leaf and the earliest live
   * transaction that touches both.
   */
  public Optional<OpeningBalanceView> openingBalanceOf(long accountId) {
    return accountService
        .findById(accountId)
        .flatMap(
            account ->
                accountService.findLeafUnderParentNamed(
                    LedgerOpeningBalanceRecorder.OPENING_BALANCES_PARENT, account.currencyCode()))
        .flatMap(leaf -> transactionRepository.findOpeningBalance(accountId, leaf.accountId()));
  }

  /** Reversibly soft-delete a transaction and (by the join) its postings (data-model §3.5). */
  @Transactional
  public void voidTransaction(long transactionId) {
    int affected = transactionRepository.softDelete(transactionId);
    if (affected == 0) {
      throw new IllegalArgumentException(
          "No live transaction with id " + transactionId + " to void");
    }
    // No total: voiding does not change one, so the id alone identifies what happened.
    LOG.debug("Transaction voided: id={}", transactionId);
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

    BalancedLegs balanced = balancedLegs(draft.postings(), baseCurrency);

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
    insertLegs(transactionId, balanced.legs());
    LOG.debug("Transaction edited: id={}, total={}", transactionId, balanced.debitTotal());
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
   * The legs to persist, plus the transaction's total. The two travel together because only the
   * branch that proved the legs balance knows which column the total reads off (data-model §8):
   * native amounts for a single-currency transaction, frozen base amounts for a cross-currency one,
   * where summing native amounts across different currencies would be meaningless.
   *
   * @param legs the very legs supplied, once proven to balance
   * @param debitTotal the sum of the debit legs — for a balanced set, the transaction's total. The
   *     one identifying amount a log line may carry (CLAUDE.md §5); no per-leg detail ever does.
   */
  private record BalancedLegs(List<PostingDraft> legs, BigDecimal debitTotal) {}

  /**
   * Validate the submitted legs and return the legs to persist — the very legs supplied, once they
   * are proven to balance (native sum-to-zero when single-currency, base sum-to-zero when
   * cross-currency). The engine adds no leg; an unbalanced set is rejected.
   */
  private BalancedLegs balancedLegs(List<PostingDraft> postings, String baseCurrency) {
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
      List<PostingDraft> legs = validatedSingleCurrency(postings);
      return new BalancedLegs(legs, debitTotal(legs, PostingDraft::amount));
    }
    List<PostingDraft> legs = validatedCrossCurrency(postings, baseCurrency);
    return new BalancedLegs(legs, debitTotal(legs, PostingDraft::baseAmount));
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

  /** The sum of the positive legs, read off whichever column the branch balanced on. */
  private static BigDecimal debitTotal(
      List<PostingDraft> postings, Function<PostingDraft, BigDecimal> column) {
    BigDecimal total = BigDecimal.ZERO;
    for (PostingDraft leg : postings) {
      BigDecimal amount = column.apply(leg);
      if (amount.signum() > 0) {
        total = total.add(amount);
      }
    }
    return total;
  }

  /**
   * Cross-currency: every leg must carry a frozen {@code baseAmount} and the base amounts must sum
   * to zero exactly (data-model §8, branch 2). The engine books no residual — a base gap is a
   * genuine conversion gain/loss the caller must record as a manual {@code FX gain/loss} line, so
   * an unbalanced set is rejected with the gap shown (data-model §6.3, 2026-07-11).
   */
  private List<PostingDraft> validatedCrossCurrency(
      List<PostingDraft> postings, String baseCurrency) {
    BigDecimal baseSum = BigDecimal.ZERO;
    BigDecimal maxBaseMagnitude = BigDecimal.ZERO;
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
      maxBaseMagnitude = maxBaseMagnitude.max(leg.baseAmount().abs());
    }

    // A base sum of zero is necessary but not sufficient: every leg carrying a base_amount of
    // exactly zero also sums to zero, yet values the whole transaction at nothing — a real native
    // amount frozen against no base is meaningless. Reject it before the sign check, or a missing
    // base total (an unfilled cross-currency field) records as a phantom all-zero-base transaction.
    if (maxBaseMagnitude.signum() == 0) {
      throw new UnbalancedTransactionException(
          "Cross-currency transaction is valued at zero: every leg's base_amount is 0,00 in "
              + baseCurrency
              + " — a base total is required (data-model §6.4)");
    }

    if (baseSum.signum() != 0) {
      throw new UnbalancedTransactionException(
          "Cross-currency transaction does not balance in base currency "
              + baseCurrency
              + ": base sum is "
              + baseSum
              + " (add a manual FX gain/loss line for the residual — data-model §6.3)");
    }
    return List.copyOf(postings);
  }

  private void insertLegs(long transactionId, List<PostingDraft> legs) {
    for (PostingDraft leg : legs) {
      long postingId = transactionRepository.insertPosting(toPosting(transactionId, leg));
      transactionRepository.insertPostingTags(postingId, leg.tagIds());
    }
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
