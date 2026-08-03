package volkovandr.hauptbuch.receipts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.accounts.AccountService;

/**
 * The Confirm gate (plan §9g) — the strict rung above 9f's lenient Save. Save persists whatever the
 * operator has typed so far; Confirm books real postings, so everything the ledger will need must
 * be there and still valid. Every finding is a <em>hard block</em> stated in plain English; the
 * receipt stays {@code processed} until they are all cleared.
 *
 * <p>Two of the checks exist because the draft can go stale between the analysis and the confirm:
 *
 * <ul>
 *   <li>a category that has been <strong>subdivided</strong> since (the {@code Car} → {@code
 *       Car:Fuel} case) is no longer postable — the currency-leaf router would otherwise silently
 *       file the line under whichever child it found first;
 *   <li>the paying account may have been changed to one in <strong>another currency</strong> than
 *       the receipt's, which is the backlogged cross-currency commit (plan §14) — the funding total
 *       in the account's currency, and the frozen base amount when neither side is base, have
 *       nowhere to be entered on this surface.
 * </ul>
 */
@Component
class ReceiptConfirmGate {

  private static final List<String> CATEGORY_TYPES = List.of("income", "expense");

  private static final String PATH_SEPARATOR = " - ";

  private final AccountService accountService;

  ReceiptConfirmGate(AccountService accountService) {
    this.accountService = accountService;
  }

  /**
   * Everything standing between this draft and a booked transaction, in reading order — empty when
   * Confirm may proceed.
   *
   * @param form the submitted editor state (the same state Save would persist)
   * @param editor the assembled readout for that form, whose {@code status} already carries the
   *     total-vs-lines verdict the live remaining shows
   */
  List<String> problems(ReceiptEditorForm form, ReceiptEditor editor) {
    List<String> problems = new ArrayList<>();
    checkDate(form, problems);
    Account payingAccount = checkAccount(form, problems);
    checkCurrency(form, payingAccount, problems);
    checkTotal(editor, problems);
    checkLines(form, problems);
    return problems;
  }

  private static void checkDate(ReceiptEditorForm form, List<String> problems) {
    if (ReceiptEditorText.parseDate(form.date()) == null) {
      problems.add("Pick the receipt's date before confirming.");
    }
  }

  private Account checkAccount(ReceiptEditorForm form, List<String> problems) {
    if (form.accountId() == null) {
      problems.add("Pick the account the receipt was paid from before confirming.");
      return null;
    }
    Account account = accountService.findById(form.accountId()).orElse(null);
    if (account == null) {
      problems.add("The paying account no longer exists — pick another one.");
    }
    return account;
  }

  private static void checkCurrency(
      ReceiptEditorForm form, Account payingAccount, List<String> problems) {
    String currency = ReceiptEditorText.blankToNull(form.currencyCode());
    if (currency == null) {
      problems.add("Pick the receipt's currency before confirming.");
      return;
    }
    if (payingAccount != null && !currency.equals(payingAccount.currencyCode())) {
      problems.add(
          "This receipt is in "
              + currency
              + " but "
              + payingAccount.name()
              + " is a "
              + payingAccount.currencyCode()
              + " account. Booking a cross-currency receipt is not implemented yet — change the"
              + " currency or the paying account.");
    }
  }

  private static void checkTotal(ReceiptEditor editor, List<String> problems) {
    if (ReceiptEditor.STATUS_NO_TOTAL.equals(editor.status())) {
      problems.add("Enter the receipt's total before confirming.");
    } else if (!ReceiptEditor.STATUS_BALANCED.equals(editor.status())) {
      problems.add(
          "The lines do not add up to the total — "
              + editor.remaining()
              + " is still unaccounted for. Fix the lines or the total before confirming.");
    }
  }

  /**
   * Every line must name something postable: a resolved category (still a posting leaf), a transfer
   * target, or a beneficiary. An unresolved line is already excluded from the remaining readout, so
   * the gap is visible on screen before Confirm ever refuses.
   */
  private void checkLines(ReceiptEditorForm form, List<String> problems) {
    List<WorkingLine> lines = WorkingLine.from(form);
    if (lines.stream().allMatch(WorkingLine::isEmpty)) {
      problems.add("A receipt needs at least one line before it can be booked.");
      return;
    }
    Set<Long> postable = postableCategoryIds();
    for (int i = 0; i < lines.size(); i++) {
      // Numbered by the line's position ON SCREEN, blanks included — a message pointing at "line 3"
      // has to mean the third row the operator can see, not the third non-blank one.
      if (!lines.get(i).isEmpty()) {
        checkLine(lines.get(i), i + 1, postable, problems);
      }
    }
  }

  private static void checkLine(
      WorkingLine line, int number, Set<Long> postable, List<String> problems) {
    if (!line.personName().isBlank()) {
      return; // a beneficiary line: the person's debt leaf is provisioned at commit
    }
    if (line.categoryId().isBlank()) {
      problems.add("Line " + number + describe(line) + " has no category yet — pick one.");
      return;
    }
    if (!line.transferDirection().isBlank()) {
      return; // a transfer to a real own account: not a category, so not subdivisible
    }
    if (!postable.contains(ReceiptEditorText.parseId(line.categoryId()))) {
      problems.add(
          "Line "
              + number
              + describe(line)
              + " points at "
              + (line.categoryText().isBlank() ? "a category" : line.categoryText())
              + ", which has been split into sub-categories since the analysis — pick one of"
              + " them.");
    }
  }

  /** {@code ("Diesel")} when the line has a description, so the message points at a real row. */
  private static String describe(WorkingLine line) {
    return line.description().isBlank() ? "" : " (\"" + line.description().strip() + "\")";
  }

  /** The category ids a posting may actually hit right now (data-model §5/§6.5). */
  private Set<Long> postableCategoryIds() {
    Set<Long> ids = new HashSet<>();
    for (AccountPath path : accountService.findPostableLeafPaths(CATEGORY_TYPES, PATH_SEPARATOR)) {
      ids.add(path.accountId());
    }
    return ids;
  }
}
