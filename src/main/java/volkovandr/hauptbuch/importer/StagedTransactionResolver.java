package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.categories.TagService;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.TransactionDraft;
import volkovandr.hauptbuch.operations.CurrencyLeafService;
import volkovandr.hauptbuch.shared.MoneyFactory;

/**
 * Translates one staged transaction (an {@link ImportTransaction} plus its {@link ImportPosting}
 * legs) into the {@link TransactionDraft} the commit hands to {@code
 * LedgerService.recordTransaction} (import.md §10; plan f2). The maps and the mapped-account
 * currencies are resolved once by {@link ImportCommitService} and passed in — this class shapes one
 * transaction.
 *
 * <p>Sign convention: staging already stores every leg in Hauptbuch's convention and the legs of a
 * staged transaction sum to zero (see {@link ImportPosting}), so a single-currency transaction's
 * legs pass straight through. A leg's target is resolved:
 *
 * <ul>
 *   <li><strong>funding leg</strong> ({@link ImportPosting#funding()}) → the mapped account, its
 *       currency the transaction's near currency;
 *   <li><strong>category leg</strong> ({@code moneyCategoryPath} set) → the mapped semantic
 *       category routed to its per-currency leaf for the near currency ({@code
 *       CurrencyLeafService}, §5.2);
 *   <li><strong>transfer leg</strong> ({@code moneyAccountName} set, not funding) → the mapped
 *       account (an ordinary account, or a person leaf c2 resolved). If its currency differs from
 *       the near currency the transaction is cross-currency (§6.2): the leg's native amount is its
 *       {@code counter_amount} (the real far amount the mirror supplied), and every leg gets a
 *       frozen {@code base_amount}.
 * </ul>
 *
 * <p><strong>Cross-currency base freezing</strong> (import.md §6.3/§6.5). The book never invents a
 * rate. For the common two-leg transfer where the far side <em>is</em> the base currency the pair
 * itself states the base value exactly ({@code counter_amount}); otherwise every non-transfer leg
 * is valued at the near→base rate ({@code ExchangeRateService.rateAsOf}) and the transfer leg's
 * base balances the rest — and if no rate is on file the whole commit is refused so the owner can
 * enter one (§6.5, "f2's to refuse, not to guess around"). Because the staged near-currency amounts
 * sum to zero, the frozen base amounts sum to zero by construction.
 *
 * <p><strong>Tags</strong> (import.md §8): each non-funding leg carries its category-map tags plus
 * a tag from the {@code /Class} suffix; the funding leg carries the intersection across the
 * non-funding legs, so a tag common to every line shows on the register row (which renders its own
 * posting's tags). A wholly {@code ?}-destroyed class name contributes nothing (§4.4/§8).
 */
@Component
class StagedTransactionResolver {

  private static final String STAGED_TRANSACTION = "Staged transaction ";

  private final CurrencyLeafService currencyLeafService;
  private final ExchangeRateService exchangeRateService;
  private final PayeeService payeeService;
  private final TagService tagService;

  StagedTransactionResolver(
      CurrencyLeafService currencyLeafService,
      ExchangeRateService exchangeRateService,
      PayeeService payeeService,
      TagService tagService) {
    this.currencyLeafService = currencyLeafService;
    this.exchangeRateService = exchangeRateService;
    this.payeeService = payeeService;
    this.tagService = tagService;
  }

  /**
   * The resolution context: the campaign's base currency, the account map ({@code Money name →
   * account id}), the mapped accounts' currencies ({@code account id → ISO code}), the category map
   * ({@code Money path → semantic category id}), and each mapped path's tag ids.
   */
  record Maps(
      String baseCurrency,
      Map<String, Long> accountIdsByName,
      Map<Long, String> currencyByAccountId,
      Map<String, Long> categoryIdsByPath,
      Map<String, List<Long>> tagIdsByPath) {}

  /** Build the draft for one staged (non-opening-balance) transaction. */
  TransactionDraft resolve(ImportTransaction transaction, List<ImportPosting> legs, Maps maps) {
    ImportPosting fundingLeg =
        legs.stream()
            .filter(ImportPosting::funding)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        STAGED_TRANSACTION
                            + transaction.importTransactionId()
                            + " has no funding leg"));
    long fundingAccountId = mappedAccount(maps, fundingLeg.moneyAccountName());
    String nearCurrency = currencyOf(maps, fundingAccountId);
    String reconciliation = transaction.clearedStatus();

    List<ResolvedLeg> nonFunding = new ArrayList<>();
    for (ImportPosting leg : legs) {
      if (!leg.funding()) {
        nonFunding.add(resolveLeg(transaction, leg, maps, nearCurrency));
      }
    }

    boolean crossCurrency = nonFunding.stream().anyMatch(ResolvedLeg::crossCurrency);
    List<PostingDraft> postings =
        crossCurrency
            ? crossCurrencyPostings(
                transaction,
                fundingAccountId,
                fundingLeg.amount(),
                reconciliation,
                nonFunding,
                maps)
            : singleCurrencyPostings(
                fundingAccountId, fundingLeg.amount(), reconciliation, nonFunding);

    // A transaction whose only non-funding legs are transfers carries no payee (data-model §3.4) —
    // Money's "Transfer to X" payee text is not a merchant. A split with any category leg keeps it.
    boolean pureTransfer = legs.stream().noneMatch(leg -> leg.moneyCategoryPath() != null);
    Long payeeId = pureTransfer ? null : payeeService.resolveImportedPayee(transaction.payeeText());

    return new TransactionDraft(
        transaction.date(), payeeId, note(transaction), "confirmed", postings);
  }

  /** Resolve one non-funding staged leg to its ledger account, native amount, currency and tags. */
  private ResolvedLeg resolveLeg(
      ImportTransaction transaction, ImportPosting leg, Maps maps, String nearCurrency) {
    List<Long> classTag = classTag(leg.className());
    if (leg.moneyCategoryPath() != null) {
      Long categoryId = maps.categoryIdsByPath().get(leg.moneyCategoryPath());
      if (categoryId == null) {
        throw new IllegalStateException(
            "Money category path \"" + leg.moneyCategoryPath() + "\" is not mapped");
      }
      long leafId = currencyLeafService.resolveCurrencyLeaf(categoryId, nearCurrency).accountId();
      List<Long> tags =
          new ArrayList<>(maps.tagIdsByPath().getOrDefault(leg.moneyCategoryPath(), List.of()));
      for (Long tagId : classTag) {
        if (!tags.contains(tagId)) {
          tags.add(tagId);
        }
      }
      return ResolvedLeg.sameCurrency(leafId, leg.amount(), leg.note(), tags);
    }

    long targetId = mappedAccount(maps, leg.moneyAccountName());
    String targetCurrency = currencyOf(maps, targetId);
    if (targetCurrency.equals(nearCurrency)) {
      return ResolvedLeg.sameCurrency(targetId, leg.amount(), leg.note(), classTag);
    }
    if (leg.counterAmount() == null) {
      throw new IllegalStateException(
          STAGED_TRANSACTION
              + transaction.importTransactionId()
              + " has an unresolved cross-currency transfer leg to \""
              + leg.moneyAccountName()
              + "\" — the commit gate should have blocked this");
    }
    return ResolvedLeg.crossCurrency(
        targetId, leg.counterAmount(), targetCurrency, leg.note(), classTag);
  }

  private List<PostingDraft> singleCurrencyPostings(
      long fundingAccountId,
      BigDecimal fundingAmount,
      String reconciliation,
      List<ResolvedLeg> nonFunding) {
    List<PostingDraft> postings = new ArrayList<>();
    postings.add(
        new PostingDraft(
            fundingAccountId, fundingAmount, null, reconciliation, null, sharedTags(nonFunding)));
    for (ResolvedLeg leg : nonFunding) {
      postings.add(
          new PostingDraft(
              leg.accountId(), leg.nativeAmount(), null, reconciliation, leg.note(), leg.tagIds()));
    }
    return postings;
  }

  private List<PostingDraft> crossCurrencyPostings(
      ImportTransaction transaction,
      long fundingAccountId,
      BigDecimal fundingAmount,
      String reconciliation,
      List<ResolvedLeg> nonFunding,
      Maps maps) {
    String base = maps.baseCurrency();
    ResolvedLeg crossLeg = onlyCrossLeg(transaction, nonFunding, base);

    // Two-leg transfer whose far side is the base currency: the observed pair states the base value
    // exactly, no rate lookup — the far leg IS base, the funding leg its negation.
    if (nonFunding.size() == 1 && base.equals(crossLeg.currencyCode())) {
      BigDecimal farBase = crossLeg.nativeAmount();
      return List.of(
          new PostingDraft(
              fundingAccountId,
              fundingAmount,
              farBase.negate(),
              reconciliation,
              null,
              sharedTags(nonFunding)),
          new PostingDraft(
              crossLeg.accountId(),
              crossLeg.nativeAmount(),
              farBase,
              reconciliation,
              crossLeg.note(),
              crossLeg.tagIds()));
    }

    // Otherwise the far leg is never the base currency (the split-with-base-leg shape was refused,
    // the 2-leg base-leg shape returned above): value the near legs at a single near→base rate and
    // let the far leg's base balance them. No observed base fact is lost.
    BigDecimal factor = nearToBaseFactor(base, currencyOf(maps, fundingAccountId), transaction);
    List<PostingDraft> postings = new ArrayList<>();
    BigDecimal fundingBase = valued(fundingAmount, factor, base);
    BigDecimal balancedBaseSum = fundingBase;
    postings.add(
        new PostingDraft(
            fundingAccountId,
            fundingAmount,
            fundingBase,
            reconciliation,
            null,
            sharedTags(nonFunding)));
    for (ResolvedLeg leg : nonFunding) {
      if (leg.crossCurrency()) {
        continue;
      }
      BigDecimal legBase = valued(leg.nativeAmount(), factor, base);
      balancedBaseSum = balancedBaseSum.add(legBase);
      postings.add(
          new PostingDraft(
              leg.accountId(),
              leg.nativeAmount(),
              legBase,
              reconciliation,
              leg.note(),
              leg.tagIds()));
    }
    postings.add(
        new PostingDraft(
            crossLeg.accountId(),
            crossLeg.nativeAmount(),
            balancedBaseSum.negate(),
            reconciliation,
            crossLeg.note(),
            crossLeg.tagIds()));
    return postings;
  }

  /**
   * The transaction's one cross-currency leg — refusing the shapes with no honest base valuation: a
   * transaction with more than one cross leg, and a cross-currency <em>split</em> whose transfer
   * leg is itself the base currency (its {@code base_amount} must equal its own amount, but the
   * near legs can only be rate-valued and the two would not sum to zero). a1 found one
   * cross-currency split leg in 20 years; the odds of hitting either shape are ~nil — the owner
   * splits it in Money and re-exports.
   */
  private static ResolvedLeg onlyCrossLeg(
      ImportTransaction transaction, List<ResolvedLeg> nonFunding, String base) {
    List<ResolvedLeg> crossLegs = nonFunding.stream().filter(ResolvedLeg::crossCurrency).toList();
    if (crossLegs.size() != 1) {
      throw new IllegalStateException(
          STAGED_TRANSACTION
              + transaction.importTransactionId()
              + " has "
              + crossLegs.size()
              + " cross-currency legs — split it in Money and re-export (import.md §6)");
    }
    ResolvedLeg crossLeg = crossLegs.get(0);
    if (nonFunding.size() > 1 && base.equals(crossLeg.currencyCode())) {
      throw new IllegalStateException(
          STAGED_TRANSACTION
              + transaction.importTransactionId()
              + " is a cross-currency split with a base-currency transfer leg — split it in Money"
              + " and re-export (import.md §6.5)");
    }
    return crossLeg;
  }

  /**
   * The near→base rate for a transaction whose currencies do not include a directly-usable base.
   */
  private BigDecimal nearToBaseFactor(
      String baseCurrency, String nearCurrency, ImportTransaction transaction) {
    if (nearCurrency.equals(baseCurrency)) {
      return BigDecimal.ONE;
    }
    return exchangeRateService
        .rateAsOf(nearCurrency, transaction.date())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No exchange rate for "
                        + nearCurrency
                        + " on or before "
                        + transaction.date()
                        + " to value the cross-currency transfer staged as transaction "
                        + transaction.importTransactionId()
                        + " — add a manual rate and retry the commit (import.md §6.5)"));
  }

  private static BigDecimal valued(BigDecimal amount, BigDecimal factor, String baseCurrency) {
    return MoneyFactory.of(amount.multiply(factor), baseCurrency).getAmount();
  }

  /** The class-name tag, or an empty list when there is no class or it was destroyed (§8). */
  private List<Long> classTag(String className) {
    if (className == null || className.isBlank() || QifText.isDestroyed(className)) {
      return new ArrayList<>();
    }
    return tagService
        .resolveChip(className)
        .map(chip -> new ArrayList<>(List.of(chip.tagId())))
        .orElseGet(ArrayList::new);
  }

  /**
   * The tags every non-funding leg carries, first-occurrence order (import.md §8) — mirrors {@code
   * ReceiptSplitEntries.sharedTags}. The funding leg gets these so a tag common to every line is
   * visible on the register row.
   */
  private static List<Long> sharedTags(List<ResolvedLeg> nonFunding) {
    if (nonFunding.isEmpty()) {
      return List.of();
    }
    Set<Long> shared = new LinkedHashSet<>(nonFunding.get(0).tagIds());
    for (ResolvedLeg leg : nonFunding) {
      shared.retainAll(leg.tagIds());
    }
    return List.copyOf(shared);
  }

  /** {@code #<ref> <memo>} / {@code #<ref>} / {@code <memo>} / null (import.md §4.2). */
  private static String note(ImportTransaction transaction) {
    String memo = transaction.note();
    String ref = transaction.referenceNumber();
    if (ref == null || ref.isBlank()) {
      return memo;
    }
    return memo == null || memo.isBlank() ? "#" + ref : "#" + ref + " " + memo;
  }

  private static long mappedAccount(Maps maps, String moneyAccountName) {
    Long id = maps.accountIdsByName().get(moneyAccountName);
    if (id == null) {
      throw new IllegalStateException("Money account \"" + moneyAccountName + "\" is not mapped");
    }
    return id;
  }

  private static String currencyOf(Maps maps, long accountId) {
    String code = maps.currencyByAccountId().get(accountId);
    if (code == null) {
      throw new IllegalStateException("No currency known for mapped account " + accountId);
    }
    return code;
  }

  /**
   * One resolved non-funding leg: its ledger account, native amount, currency, posting note and
   * tags. {@code crossCurrency} marks a transfer leg whose account is in a currency other than the
   * transaction's near currency — its {@code nativeAmount} is then the real far amount ({@code
   * counter_amount}), not the near-currency figure staging holds.
   */
  private record ResolvedLeg(
      long accountId,
      BigDecimal nativeAmount,
      String currencyCode,
      String note,
      List<Long> tagIds,
      boolean crossCurrency) {

    static ResolvedLeg sameCurrency(
        long accountId, BigDecimal amount, String note, List<Long> tags) {
      return new ResolvedLeg(accountId, amount, null, note, List.copyOf(tags), false);
    }

    static ResolvedLeg crossCurrency(
        long accountId,
        BigDecimal counterAmount,
        String currencyCode,
        String note,
        List<Long> tags) {
      return new ResolvedLeg(accountId, counterAmount, currencyCode, note, List.copyOf(tags), true);
    }
  }
}
