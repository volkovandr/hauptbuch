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
import volkovandr.hauptbuch.operations.SplitCurrency;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
import volkovandr.hauptbuch.operations.SplitLineView;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Assembles the {@link ReceiptEditor} view model the wrapper renders (plan §9f) — the read side of
 * the post-process editor. Each line becomes the shared {@link SplitLineView} (reused verbatim from
 * the register's split panel) plus this surface's ghost; the readout sums the lines' signed
 * contributions with the register's own {@link SplitLineAmounts} mixed-split rule, so the
 * server-authoritative {@code remaining} agrees with what the keyboard.js leaf recomputes live.
 */
@Component
class ReceiptEditorAssembler {

  private static final int FRACTION_DIGITS = 2;

  private final AccountService accountService;
  private final LedgerService ledgerService;

  ReceiptEditorAssembler(AccountService accountService, LedgerService ledgerService) {
    this.accountService = accountService;
    this.ledgerService = ledgerService;
  }

  /** Assemble the editor view model for the current form state. */
  ReceiptEditor panel(ReceiptEditorForm form) {
    Map<Long, String> labels = tagLabels(form);
    List<ReceiptEditorLine> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (int i = 0; i < form.lineCount(); i++) {
      net = net.add(contribution(form, i));
      lines.add(lineView(form, i, labels));
    }

    boolean hasTotal = form.total() != null && !form.total().isBlank();
    BigDecimal total = hasTotal ? ReceiptEditorText.parse(form.total()) : BigDecimal.ZERO;
    BigDecimal remaining = total.subtract(net.abs());
    boolean balanced = remaining.signum() == 0;
    String currency = ReceiptEditorText.orEmpty(form.currencyCode());
    return new ReceiptEditor(
        ReceiptEditorText.parseDate(form.date()),
        ReceiptEditorText.orEmpty(form.payeeText()),
        form.accountId(),
        new SplitCurrency(false, currency, currency, currency, false, "", "", "", "", "0", "0"),
        MoneyFormat.number(total, FRACTION_DIGITS),
        ReceiptEditorText.orEmpty(form.note()),
        ReceiptEditorText.orEmpty(form.receiptNumber()),
        MoneyFormat.number(remaining, FRACTION_DIGITS),
        balanced,
        status(hasTotal, balanced),
        currencyMismatch(form),
        lines);
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

  private ReceiptEditorLine lineView(ReceiptEditorForm form, int i, Map<Long, String> labels) {
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
            "",
            "",
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

  private boolean currencyMismatch(ReceiptEditorForm form) {
    String currency = ReceiptEditorText.blankToNull(form.currencyCode());
    if (currency == null || form.accountId() == null) {
      return false;
    }
    String account =
        accountService.findById(form.accountId()).map(Account::currencyCode).orElse(null);
    return account != null && !currency.equals(account);
  }
}
