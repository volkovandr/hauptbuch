package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.TransactionTag;
import volkovandr.hauptbuch.operations.SplitCurrencyContext;
import volkovandr.hauptbuch.operations.SplitCurrencyQuery;
import volkovandr.hauptbuch.operations.SplitCurrencyService;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
import volkovandr.hauptbuch.operations.SplitLineView;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Assembles the {@link ReceiptEditor} view model the wrapper renders (plan §9f) — the read side of
 * the post-process editor. Each line becomes the shared {@link SplitLineView} (reused verbatim from
 * the register's split panel) plus this surface's ghost; the readout sums the lines' signed
 * contributions with the register's own {@link SplitLineAmounts} mixed-split rule, so the
 * server-authoritative {@code remaining} agrees with what the keyboard.js leaf recomputes live.
 *
 * <p><strong>Cross-currency (issue receipts/23).</strong> A receipt billed in one currency and paid
 * from an account in another gets the register's own header, resolved by the shared {@link
 * SplitCurrencyService} — one rule, two surfaces (decision 4). The line amounts stay in the
 * receipt's currency and gain their read-only funding/base equivalents, exactly as the split
 * panel's do. There is deliberately no mismatch <em>warning</em> any more (decision 9): the fields
 * appearing are the signal, and warning about a fully supported mode only teaches the operator to
 * ignore warnings.
 */
@Component
class ReceiptEditorAssembler {

  private static final int FRACTION_DIGITS = 2;

  private final AccountService accountService;
  private final LedgerService ledgerService;
  private final SplitCurrencyService splitCurrencyService;

  ReceiptEditorAssembler(
      AccountService accountService,
      LedgerService ledgerService,
      SplitCurrencyService splitCurrencyService) {
    this.accountService = accountService;
    this.ledgerService = ledgerService;
    this.splitCurrencyService = splitCurrencyService;
  }

  /** Assemble the editor view model for the current form state. */
  ReceiptEditor panel(ReceiptEditorForm form) {
    SplitCurrencyContext ctx =
        splitCurrencyService.resolve(
            new SplitCurrencyQuery(
                fundingCurrency(form),
                form.currencyCode(),
                form.total(),
                form.fundingTotal(),
                form.baseTotal()));
    Map<Long, String> labels = tagLabels(form);
    List<ReceiptEditorLine> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (int i = 0; i < form.lineCount(); i++) {
      net = net.add(contribution(form, i));
      lines.add(lineView(form, i, ctx, labels));
    }

    boolean hasTotal = form.total() != null && !form.total().isBlank();
    BigDecimal total = hasTotal ? ReceiptEditorText.parse(form.total()) : BigDecimal.ZERO;
    BigDecimal netMagnitude = net.abs();
    BigDecimal remaining = total.subtract(netMagnitude);
    boolean balanced = remaining.signum() == 0;
    return new ReceiptEditor(
        ReceiptEditorText.parseDate(form.date()),
        ReceiptEditorText.orEmpty(form.payeeText()),
        form.accountId(),
        ctx.view(netMagnitude),
        MoneyFormat.number(total, FRACTION_DIGITS),
        ReceiptEditorText.orEmpty(form.note()),
        ReceiptEditorText.orEmpty(form.receiptNumber()),
        MoneyFormat.number(remaining, FRACTION_DIGITS),
        balanced,
        status(hasTotal, balanced),
        lines);
  }

  /**
   * The funding leg's currency: the paying account's own. A receipt's funding leg is always a real
   * own account — a beneficiary is a per-line attribution, never the header — so unlike the
   * register's split panel there is no funding-person branch here. Blank until an account is
   * picked, which keeps the header single-currency (and its chrome hidden) until then.
   */
  private String fundingCurrency(ReceiptEditorForm form) {
    if (form.accountId() == null) {
      return ReceiptEditorText.orEmpty(form.currencyCode());
    }
    return accountService
        .findById(form.accountId())
        .map(Account::currencyCode)
        .orElseGet(() -> ReceiptEditorText.orEmpty(form.currencyCode()));
  }

  /** The readout's three-valued verdict, whose vocabulary {@link ReceiptEditor} owns. */
  private static String status(boolean hasTotal, boolean balanced) {
    if (!hasTotal) {
      return ReceiptEditor.STATUS_NO_TOTAL;
    }
    return balanced ? ReceiptEditor.STATUS_BALANCED : ReceiptEditor.STATUS_UNBALANCED;
  }

  private static BigDecimal contribution(ReceiptEditorForm form, int i) {
    return SplitLineAmounts.lenientContribution(
        ReceiptEditorForm.at(form.lineAmount(), i),
        ReceiptEditorForm.at(form.lineCategoryType(), i),
        ReceiptEditorForm.at(form.lineTransferDirection(), i),
        ReceiptEditorForm.at(form.linePersonDirection(), i));
  }

  private ReceiptEditorLine lineView(
      ReceiptEditorForm form, int i, SplitCurrencyContext ctx, Map<Long, String> labels) {
    BigDecimal magnitude =
        ReceiptEditorText.parse(ReceiptEditorForm.at(form.lineAmount(), i)).abs();
    SplitLineView view =
        new SplitLineView(
            i,
            ReceiptEditorForm.at(form.categoryText(), i),
            ReceiptEditorForm.at(form.lineCategoryId(), i),
            ReceiptEditorForm.at(form.lineCategoryType(), i),
            ReceiptEditorForm.at(form.lineTransferDirection(), i),
            ReceiptEditorForm.at(form.linePersonName(), i),
            ReceiptEditorForm.at(form.linePersonDirection(), i),
            ReceiptEditorForm.at(form.linePersonRevive(), i),
            ReceiptEditorForm.at(form.lineAmount(), i),
            ReceiptEditorForm.at(form.lineNote(), i),
            ctx.derivedFunding(magnitude),
            ctx.derivedBase(magnitude),
            pills(form.tagsAt(i), labels));
    return new ReceiptEditorLine(
        view,
        ReceiptEditorText.blankToNull(ReceiptEditorForm.at(form.lineAiTargetText(), i)),
        ReceiptEditorForm.at(form.lineDescription(), i));
  }

  private Map<Long, String> tagLabels(ReceiptEditorForm form) {
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < form.lineCount(); i++) {
      ids.addAll(form.tagsAt(i));
    }
    return ledgerService.labelsForTagIds(ids);
  }

  private static List<TransactionTag> pills(List<Long> ids, Map<Long, String> labels) {
    List<TransactionTag> pills = new ArrayList<>();
    for (Long id : ids) {
      String label = labels.get(id);
      if (label != null) {
        pills.add(new TransactionTag(id, label));
      }
    }
    return pills;
  }
}
