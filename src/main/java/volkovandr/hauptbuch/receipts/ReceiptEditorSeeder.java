package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.debts.Person;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.debts.PersonTarget;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.TransferTarget;
import volkovandr.hauptbuch.receipts.repository.ReceiptLineRepository;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Seeds the post-process editor form from a receipt's denormalised header and its stored draft
 * lines (plan §9f) — the read half of {@link ReceiptEditorService}. Each stored line becomes a
 * {@link WorkingLine} the shared line-editor fragment can render: a category leaf shows its {@code
 * Parent - Child} path, a real-account target shows the {@code To →} transfer sigil, and a
 * beneficiary shows the {@code for <person>} sigil — the register's own datalist formats ({@link
 * TransferTarget}/{@link PersonTarget}), so the picker re-resolves them identically.
 */
@Component
class ReceiptEditorSeeder {

  private static final List<String> CATEGORY_TYPES = List.of("income", "expense");
  private static final String PATH_SEPARATOR = " - ";
  private static final int FRACTION_DIGITS = 2;
  private static final String INCOME = "income";
  private static final String EXPENSE = "expense";

  private final AccountService accountService;
  private final PersonService personService;
  private final PayeeService payeeService;
  private final ReceiptLineRepository receiptLineRepository;

  ReceiptEditorSeeder(
      AccountService accountService,
      PersonService personService,
      PayeeService payeeService,
      ReceiptLineRepository receiptLineRepository) {
    this.accountService = accountService;
    this.personService = personService;
    this.payeeService = payeeService;
    this.receiptLineRepository = receiptLineRepository;
  }

  /** Build the editor form from a receipt's header and its stored draft lines. */
  ReceiptEditorForm seed(Receipt receipt, List<ReceiptLine> lines) {
    Map<Long, String> paths = categoryPaths();
    List<WorkingLine> working = new ArrayList<>();
    for (ReceiptLine line : lines) {
      working.add(seedLine(line, paths));
    }
    return WorkingLine.toForm(
        new ReceiptEditorHeader(
            receipt.receiptDate() == null ? "" : receipt.receiptDate().toString(),
            payeeText(receipt),
            receipt.accountId(),
            headerCurrency(receipt),
            amountText(receipt.totalAmount()),
            // The persisted cross-currency totals (issue receipts/23, decision 2), so reopening a
            // processed receipt shows the funding total the operator overtyped rather than a fresh
            // proposal derived from today's rates.
            amountText(receipt.fundingTotal()),
            amountText(receipt.baseTotal()),
            ReceiptEditorText.orEmpty(receipt.note()),
            ReceiptEditorText.orEmpty(receipt.receiptNumber())),
        working);
  }

  private static String amountText(BigDecimal amount) {
    return amount == null ? "" : MoneyFormat.number(amount, FRACTION_DIGITS);
  }

  private String payeeText(Receipt receipt) {
    // The parsed merchant as a payee reads — name - city - country — so every part the AI saw
    // prefills, not just the name (owner feedback 2026-08-02). The register's payee format is the
    // same, so Save resolves it without the operator retyping the city/country.
    String merchant = ReceiptEditorText.orEmpty(receipt.merchantDisplay());
    if (receipt.payeeId() != null) {
      return payeeService.entryValueFor(receipt.payeeId()).orElse(merchant);
    }
    // First open: the payee picker prefills from the parsed merchant (nothing persists until Save).
    return merchant;
  }

  private String headerCurrency(Receipt receipt) {
    if (receipt.currencyCode() != null) {
      return receipt.currencyCode();
    }
    if (receipt.accountId() == null) {
      return "";
    }
    return accountService.findById(receipt.accountId()).map(Account::currencyCode).orElse("");
  }

  private WorkingLine seedLine(ReceiptLine line, Map<Long, String> paths) {
    WorkingLine base =
        new WorkingLine(
            ReceiptEditorText.orEmpty(line.description()),
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            MoneyFormat.number(line.amount(), FRACTION_DIGITS),
            ReceiptEditorText.orEmpty(line.note()),
            ReceiptEditorText.orEmpty(line.aiTargetText()),
            receiptLineRepository.findTagIds(line.receiptLineId()));
    if (line.personId() != null) {
      String name = personService.findById(line.personId()).map(Person::name).orElse("");
      return base.withCategoryText(PersonTarget.option(PersonTarget.Direction.FOR, name))
          .withPerson(name, PersonTarget.Direction.FOR.name());
    }
    if (line.accountId() != null) {
      return seedTargetLine(line.accountId(), base, paths);
    }
    return base; // uncategorised — the ghost hint carries what the AI said
  }

  private WorkingLine seedTargetLine(long accountId, WorkingLine base, Map<Long, String> paths) {
    Optional<Account> account = accountService.findById(accountId);
    if (account.isEmpty()) {
      return base;
    }
    Account target = account.get();
    if (INCOME.equals(target.type()) || EXPENSE.equals(target.type())) {
      String text = paths.getOrDefault(accountId, target.name());
      return base.withCategoryText(text).withCategory(String.valueOf(accountId), target.type());
    }
    // A real own account target ⇒ a transfer leg (e.g. a supermarket-cashback line, §13.4).
    return base.withCategoryText(TransferTarget.option(TransferTarget.Direction.TO, target.name()))
        .withTransfer(String.valueOf(accountId), TransferTarget.Direction.TO.name());
  }

  private Map<Long, String> categoryPaths() {
    Map<Long, String> paths = new HashMap<>();
    for (AccountPath path : accountService.findPostableLeafPaths(CATEGORY_TYPES, PATH_SEPARATOR)) {
      paths.put(path.accountId(), path.path());
    }
    return paths;
  }
}
