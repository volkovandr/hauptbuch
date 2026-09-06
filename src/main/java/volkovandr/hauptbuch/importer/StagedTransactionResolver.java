package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.categories.TagService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.PostingDraft;
import volkovandr.hauptbuch.ledger.TransactionDraft;
import volkovandr.hauptbuch.operations.CurrencyLeafService;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Translates one staged transaction (an {@link ImportTransaction} plus its {@link ImportPosting}
 * legs) into the {@link TransactionDraft} the commit hands to {@code
 * LedgerService.recordTransaction} (import.md §10; plan f2). The maps and the mapped-account
 * currencies are resolved once by {@link ImportCommitService} and passed in — this class shapes one
 * transaction; {@link CrossCurrencyBaseAmounts} freezes the base amounts of a cross-currency one.
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
 *       the near currency the transaction is cross-currency (§6.2) and {@link
 *       CrossCurrencyBaseAmounts} takes over.
 * </ul>
 *
 * <p><strong>Tags</strong> (import.md §8): each non-funding leg carries its category-map tags plus
 * a tag from the {@code /Class} suffix; the funding leg carries the intersection across the
 * non-funding legs ({@link ImportResolvedLeg#sharedTags}), so a tag common to every line shows on
 * the register row. A wholly {@code ?}-destroyed class name contributes nothing (§4.4/§8).
 */
@Component
class StagedTransactionResolver {

  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final CurrencyLeafService currencyLeafService;
  private final CrossCurrencyBaseAmounts crossCurrencyBaseAmounts;
  private final PayeeService payeeService;
  private final TagService tagService;

  StagedTransactionResolver(
      CurrencyLeafService currencyLeafService,
      CrossCurrencyBaseAmounts crossCurrencyBaseAmounts,
      PayeeService payeeService,
      TagService tagService) {
    this.currencyLeafService = currencyLeafService;
    this.crossCurrencyBaseAmounts = crossCurrencyBaseAmounts;
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
                        "The staged transaction "
                            + transaction.importTransactionId()
                            + " has no funding leg"));
    String where = describe(transaction, fundingLeg, legs);
    long fundingAccountId = mappedAccount(maps, fundingLeg.moneyAccountName());
    String nearCurrency = currencyOf(maps, fundingAccountId);
    String reconciliation = transaction.clearedStatus();

    List<ImportResolvedLeg> nonFunding = new ArrayList<>();
    for (ImportPosting leg : legs) {
      if (!leg.funding()) {
        nonFunding.add(resolveLeg(leg, maps, nearCurrency, where));
      }
    }

    boolean crossCurrency = nonFunding.stream().anyMatch(ImportResolvedLeg::crossCurrency);
    List<PostingDraft> postings =
        crossCurrency
            ? crossCurrencyBaseAmounts.postings(
                where,
                fundingAccountId,
                fundingLeg.amount(),
                nearCurrency,
                reconciliation,
                nonFunding,
                maps.baseCurrency(),
                transaction.date())
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
  private ImportResolvedLeg resolveLeg(
      ImportPosting leg, Maps maps, String nearCurrency, String where) {
    List<Long> classTag = classTag(leg.className());
    if (leg.moneyCategoryPath() != null) {
      Long categoryId = maps.categoryIdsByPath().get(leg.moneyCategoryPath());
      if (categoryId == null) {
        throw new IllegalStateException(
            "The Money category path \""
                + leg.moneyCategoryPath()
                + "\" is not mapped — needed by "
                + where);
      }
      long leafId = currencyLeafService.resolveCurrencyLeaf(categoryId, nearCurrency).accountId();
      List<Long> tags =
          new ArrayList<>(maps.tagIdsByPath().getOrDefault(leg.moneyCategoryPath(), List.of()));
      for (Long tagId : classTag) {
        if (!tags.contains(tagId)) {
          tags.add(tagId);
        }
      }
      return ImportResolvedLeg.sameCurrency(leafId, leg.amount(), leg.note(), tags);
    }

    long targetId = mappedAccount(maps, leg.moneyAccountName());
    String targetCurrency = currencyOf(maps, targetId);
    if (targetCurrency.equals(nearCurrency)) {
      return ImportResolvedLeg.sameCurrency(targetId, leg.amount(), leg.note(), classTag);
    }
    if (leg.counterAmount() == null) {
      throw new IllegalStateException(
          where
              + " has an unresolved cross-currency transfer leg to Money account \""
              + leg.moneyAccountName()
              + "\" — the commit gate should have blocked this");
    }
    return ImportResolvedLeg.crossCurrency(
        targetId, leg.counterAmount(), targetCurrency, leg.note(), classTag);
  }

  private static List<PostingDraft> singleCurrencyPostings(
      long fundingAccountId,
      BigDecimal fundingAmount,
      String reconciliation,
      List<ImportResolvedLeg> nonFunding) {
    List<PostingDraft> postings = new ArrayList<>();
    postings.add(
        new PostingDraft(
            fundingAccountId,
            fundingAmount,
            null,
            reconciliation,
            null,
            ImportResolvedLeg.sharedTags(nonFunding)));
    for (ImportResolvedLeg leg : nonFunding) {
      postings.add(
          new PostingDraft(
              leg.accountId(), leg.nativeAmount(), null, reconciliation, leg.note(), leg.tagIds()));
    }
    return postings;
  }

  /**
   * A human locator for an error: the staged id, the date, the funding Money account, the amount,
   * the payee, and any transfer counterparties — so the owner can find and fix the transaction in
   * Money.
   */
  private static String describe(
      ImportTransaction transaction, ImportPosting fundingLeg, List<ImportPosting> legs) {
    String locator =
        "the staged transaction "
            + transaction.importTransactionId()
            + " dated "
            + GERMAN_DATE.format(transaction.date())
            + " from Money account \""
            + fundingLeg.moneyAccountName()
            + "\" for "
            + MoneyFormat.number(fundingLeg.amount(), 2);
    if (transaction.payeeText() != null && !transaction.payeeText().isBlank()) {
      locator += " (payee \"" + transaction.payeeText() + "\")";
    }
    String transferTargets =
        legs.stream()
            .filter(leg -> !leg.funding() && leg.moneyAccountName() != null)
            .map(ImportPosting::moneyAccountName)
            .distinct()
            .reduce((a, b) -> a + "\", \"" + b)
            .orElse("");
    return transferTargets.isEmpty()
        ? locator
        : locator + ", transfer to \"" + transferTargets + "\"";
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
}
