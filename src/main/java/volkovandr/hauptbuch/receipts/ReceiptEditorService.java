package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.debts.PersonMatch;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
import volkovandr.hauptbuch.receipts.repository.ReceiptLineRepository;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The post-process editor's orchestration (plan §9f): the entry point the controller drives — seed
 * the form from stored lines ({@link ReceiptEditorSeeder}), assemble the view model ({@link
 * ReceiptEditorAssembler}), run the add/remove/redistribute round-trips over the unsaved form
 * ({@link WorkingLine}), and persist Save (delete-and-reinsert). Save reviews the draft only; it
 * does not advance the state — {@code committed} is 9g's Confirm.
 *
 * <p>Drafts store the <em>semantic</em> category id (what {@code /categories/resolve} yields), not
 * a currency leaf: the per-currency leaf is resolved at 9g's Confirm "at post time", so Save stores
 * each line's resolved id directly, or resolves a person name → id for a beneficiary. Single-
 * currency only this slice (cross-currency receipt commits are backlogged, plan §14).
 */
@Service
@Transactional
public class ReceiptEditorService {

  private static final int FRACTION_DIGITS = 2;

  private final ReceiptEditorSeeder seeder;
  private final ReceiptEditorAssembler assembler;
  private final PersonService personService;
  private final PayeeService payeeService;
  private final ReceiptRepository receiptRepository;
  private final ReceiptLineRepository receiptLineRepository;

  ReceiptEditorService(
      ReceiptEditorSeeder seeder,
      ReceiptEditorAssembler assembler,
      PersonService personService,
      PayeeService payeeService,
      ReceiptRepository receiptRepository,
      ReceiptLineRepository receiptLineRepository) {
    this.seeder = seeder;
    this.assembler = assembler;
    this.personService = personService;
    this.payeeService = payeeService;
    this.receiptRepository = receiptRepository;
    this.receiptLineRepository = receiptLineRepository;
  }

  /** Build the editor form from a receipt's header and its stored draft lines. */
  public ReceiptEditorForm seed(Receipt receipt, List<ReceiptLine> lines) {
    return seeder.seed(receipt, lines);
  }

  /** Assemble the editor view model for the current form state. */
  public ReceiptEditor panel(ReceiptEditorForm form) {
    return assembler.panel(form);
  }

  /** Append a blank line whose amount defaults to "the rest" (total − allocated), if positive. */
  public ReceiptEditorForm addLine(ReceiptEditorForm form) {
    String remaining = assembler.panel(form).remaining();
    String rest = ReceiptEditorText.parse(remaining).signum() > 0 ? remaining : "";
    List<WorkingLine> working = WorkingLine.from(form);
    working.add(WorkingLine.blank(rest));
    return reform(form, working);
  }

  /** Remove the line at {@code index}. */
  public ReceiptEditorForm removeLine(ReceiptEditorForm form, int index) {
    List<WorkingLine> working = WorkingLine.from(form);
    if (index >= 0 && index < working.size()) {
      working.remove(index);
    }
    return reform(form, working);
  }

  /**
   * Redistribute the line at {@code index} over the others and drop it (plan §9f). Real-account
   * transfer legs never absorb; the surviving amounts replace the form's.
   *
   * @throws LineRedistribution.RedistributeRefusedException when nothing can absorb the amount
   */
  public ReceiptEditorForm redistribute(ReceiptEditorForm form, int index) {
    List<WorkingLine> working = WorkingLine.from(form);
    List<BigDecimal> survivors = LineRedistribution.spread(redistributeInput(working), index);
    working.remove(index);
    for (int i = 0; i < working.size(); i++) {
      working.set(
          i, working.get(i).withAmount(MoneyFormat.number(survivors.get(i), FRACTION_DIGITS)));
    }
    return reform(form, working);
  }

  private static List<LineRedistribution.Line> redistributeInput(List<WorkingLine> working) {
    List<LineRedistribution.Line> input = new ArrayList<>();
    for (WorkingLine line : working) {
      input.add(redistributeLine(line));
    }
    return input;
  }

  private static LineRedistribution.Line redistributeLine(WorkingLine line) {
    return new LineRedistribution.Line(
        ReceiptEditorText.parse(line.amount()), !line.transferDirection().isBlank());
  }

  /**
   * Persist the reviewed draft: header edits + a full rewrite of the lines. Stays {@code
   * processed}.
   *
   * <p>Refuses a {@code committed} receipt. Its draft is the middle link of the audit chain ({@code
   * parse_raw} → {@code receipt_line} → postings, data-model §13.2), so rewriting it would leave
   * the booked transaction described by lines it was never built from. The committed view renders
   * the editor disabled, but that is a display rule — this is the invariant (CLAUDE.md §1.7).
   * Reopen first; then Re-enter re-books from whatever the draft became.
   *
   * @throws IllegalStateException if the receipt is {@code committed}
   */
  public void save(long receiptId, ReceiptEditorForm form) {
    if (receiptRepository
        .findById(receiptId)
        .filter(r -> ReceiptState.COMMITTED.equals(r.state()))
        .isPresent()) {
      throw new IllegalStateException(
          "A committed receipt's draft is the record of what was booked — reopen it before"
              + " editing");
    }
    Long payeeId =
        ReceiptEditorText.blankToNull(form.payeeText()) == null
            ? null
            : payeeService.resolvePayee(null, form.payeeText());
    receiptRepository.saveEditorHeader(
        receiptId,
        new ReceiptHeaderDraft(
            ReceiptEditorText.parseDate(form.date()),
            payeeId,
            form.accountId(),
            ReceiptEditorText.blankToNull(form.currencyCode()),
            ReceiptEditorText.blankToNull(form.total()) == null
                ? null
                : ReceiptEditorText.parse(form.total()),
            ReceiptEditorText.blankToNull(form.note()),
            ReceiptEditorText.blankToNull(form.receiptNumber())));

    receiptLineRepository.deleteByReceiptId(receiptId);
    int sortOrder = 0;
    for (WorkingLine line : WorkingLine.from(form)) {
      if (line.isEmpty()) {
        continue;
      }
      long lineId = receiptLineRepository.insert(receiptId, draftOf(line, sortOrder));
      for (Long tagId : line.tags()) {
        receiptLineRepository.insertTag(lineId, tagId);
      }
      sortOrder++;
    }
  }

  private ReceiptLineDraft draftOf(WorkingLine line, int sortOrder) {
    Long personId = null;
    Long accountId = null;
    if (!line.personName().isBlank()) {
      personId = resolvePerson(line.personName());
    } else if (!line.categoryId().isBlank()) {
      accountId = Long.valueOf(line.categoryId());
    }
    return new ReceiptLineDraft(
        ReceiptEditorText.blankToNull(line.description()),
        line.amount().isBlank()
            ? BigDecimal.ZERO
            : SplitLineAmounts.parseSignedAmount(line.amount()),
        accountId,
        personId,
        ReceiptEditorText.blankToNull(line.note()),
        sortOrder,
        line.tags(),
        ReceiptEditorText.blankToNull(line.aiTargetText()));
  }

  private long resolvePerson(String name) {
    return personService.matchExact(name) instanceof PersonMatch.Live live
        ? live.person().personId()
        : personService.create(name).personId();
  }

  private static ReceiptEditorForm reform(ReceiptEditorForm form, List<WorkingLine> working) {
    return WorkingLine.toForm(
        form.date(),
        form.payeeText(),
        form.accountId(),
        form.currencyCode(),
        form.total(),
        form.note(),
        form.receiptNumber(),
        working);
  }
}
