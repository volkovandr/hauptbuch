package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.debts.PersonMatch;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.operations.SplitCurrencyContext;
import volkovandr.hauptbuch.operations.SplitCurrencyService;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
import volkovandr.hauptbuch.operations.SplitTotals;
import volkovandr.hauptbuch.operations.SplitTotalsQuery;
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
 * each line's resolved id directly, or resolves a person name → id for a beneficiary.
 *
 * <p>A receipt billed in another currency than the paying account's carries two more header numbers
 * (issue receipts/23) — what came off the account and the base figure freezing the conversion. Both
 * are proposed from the rate feed while blank ({@link #proposeTotals}) and persisted by Save, so an
 * overtyped estimate survives a reopen.
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
  private final SplitCurrencyService splitCurrencyService;

  ReceiptEditorService(
      ReceiptEditorSeeder seeder,
      ReceiptEditorAssembler assembler,
      PersonService personService,
      PayeeService payeeService,
      ReceiptRepository receiptRepository,
      ReceiptLineRepository receiptLineRepository,
      SplitCurrencyService splitCurrencyService) {
    this.seeder = seeder;
    this.assembler = assembler;
    this.personService = personService;
    this.payeeService = payeeService;
    this.receiptRepository = receiptRepository;
    this.receiptLineRepository = receiptLineRepository;
    this.splitCurrencyService = splitCurrencyService;
  }

  /**
   * Build the editor form from a receipt's header and its stored draft lines.
   *
   * <p>A cross-currency receipt gets its blank totals proposed here too (issue receipts/23), so
   * opening one the AI already detected as foreign-card shows the {@code Off account} figure
   * immediately rather than an empty required field the operator must poke to fill. Anything
   * already persisted is left exactly as it was saved — the proposal only ever fills a blank.
   */
  public ReceiptEditorForm seed(Receipt receipt, List<ReceiptLine> lines) {
    return proposeTotals(seeder.seed(receipt, lines));
  }

  /** Assemble the editor view model for the current form state. */
  public ReceiptEditor panel(ReceiptEditorForm form) {
    return assembler.panel(form);
  }

  /**
   * Fill whichever cross-currency header total is still blank from the rate feed (issue
   * receipts/23) and return the form carrying them — the editor's currency round-trip. The
   * proposals themselves are {@code operations}' one rule, shared with the register's split panel,
   * so the two surfaces cannot propose different numbers.
   */
  public ReceiptEditorForm proposeTotals(ReceiptEditorForm form) {
    SplitTotals totals =
        splitCurrencyService.proposeTotals(
            new SplitTotalsQuery(
                form.accountId(),
                form.currencyCode(),
                ReceiptEditorText.parseDate(form.date()),
                form.total(),
                form.fundingTotal(),
                form.baseTotal()));
    return WorkingLine.toForm(
        ReceiptEditorHeader.withTotals(form, totals.fundingTotal(), totals.baseTotal()),
        WorkingLine.from(form));
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
    // The cross-currency totals persist only while the header actually IS cross-currency (issue
    // receipts/23): switching the paying account back to the receipt's own currency must clear
    // them, or a single-currency receipt would carry a stale funding total nothing renders.
    SplitCurrencyContext currency = assembler.currency(form);
    receiptRepository.saveEditorHeader(
        receiptId,
        new ReceiptHeaderDraft(
            ReceiptEditorText.parseDate(form.date()),
            payeeId,
            form.accountId(),
            ReceiptEditorText.blankToNull(form.currencyCode()),
            amountOrNull(form.total()),
            ReceiptEditorText.blankToNull(form.note()),
            ReceiptEditorText.blankToNull(form.receiptNumber()),
            currency.cross() ? amountOrNull(form.fundingTotal()) : null,
            currency.neitherIsBase() ? amountOrNull(form.baseTotal()) : null));

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

  /** A typed header amount, or null when the operator left the field blank. */
  private static BigDecimal amountOrNull(String text) {
    return ReceiptEditorText.blankToNull(text) == null ? null : ReceiptEditorText.parse(text);
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
    return WorkingLine.toForm(ReceiptEditorHeader.of(form), working);
  }
}
