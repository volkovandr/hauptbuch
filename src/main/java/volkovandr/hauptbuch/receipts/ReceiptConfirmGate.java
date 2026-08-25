package volkovandr.hauptbuch.receipts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.AccountPath;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.SplitCurrency;

/**
 * The Confirm gate (plan §9g) — the strict rung above 9f's lenient Save. Save persists whatever the
 * operator has typed so far; Confirm books real postings, so everything the ledger will need must
 * be there and still valid. Every finding is a <em>hard block</em> stated in plain English; the
 * receipt stays {@code processed} until they are all cleared.
 *
 * <p>One check exists because the draft can go stale between the analysis and the confirm: a
 * category that has been <strong>subdivided</strong> since (the {@code Car} → {@code Car:Fuel}
 * case) is no longer postable — the currency-leaf router would otherwise silently file the line
 * under whichever child it found first.
 *
 * <p>The rest are the <strong>cross-currency</strong> checks (issue receipts/23, decision 8). A
 * receipt billed in another currency than the paying account's books through {@code
 * DockSplitService}'s cross-currency path, which throws raw on four things; each is restated here
 * in plain English so the operator lands on the editor with a list, never on an error page
 * mid-Confirm. Before this issue the gate simply refused the whole mode.
 */
@Component
class ReceiptConfirmGate {

  private static final List<String> CATEGORY_TYPES = List.of("income", "expense");

  private static final String PATH_SEPARATOR = " - ";

  private final AccountService accountService;
  private final SettingsService settingsService;

  ReceiptConfirmGate(AccountService accountService, SettingsService settingsService) {
    this.accountService = accountService;
    this.settingsService = settingsService;
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
    checkAccount(form, problems);
    checkCurrency(form, editor.currency(), problems);
    checkTotal(editor, problems);
    checkLines(form, editor.currency().spendingCurrencyCode(), problems);
    return problems;
  }

  private static void checkDate(ReceiptEditorForm form, List<String> problems) {
    if (ReceiptEditorText.parseDate(form.date()) == null) {
      problems.add("Pick the receipt's date before confirming.");
    }
  }

  private void checkAccount(ReceiptEditorForm form, List<String> problems) {
    if (form.accountId() == null) {
      problems.add("Pick the account the receipt was paid from before confirming.");
      return;
    }
    if (accountService.findById(form.accountId()).isEmpty()) {
      problems.add("The paying account no longer exists — pick another one.");
    }
  }

  /**
   * The header currency, and — when it differs from the paying account's — the two things a
   * cross-currency booking needs that a same-currency one does not (issue receipts/23): a base
   * currency to balance in, and the header totals that freeze the conversion. A total left blank
   * because no stored rate could propose one reads as zero here, which is the same block: the
   * operator has to supply the number rather than the app inventing it.
   */
  private void checkCurrency(ReceiptEditorForm form, SplitCurrency header, List<String> problems) {
    String currency = ReceiptEditorText.blankToNull(form.currencyCode());
    if (currency == null) {
      problems.add("Pick the receipt's currency before confirming.");
      return;
    }
    if (!header.crossCurrency()) {
      return;
    }
    if (settingsService.baseCurrency().isEmpty()) {
      problems.add(
          "This receipt is in "
              + currency
              + " but the paying account is in "
              + header.fundingCurrencyCode()
              + ", and the book has no base currency — set one in Settings before confirming a"
              + " cross-currency receipt.");
    }
    if (isZero(header.fundingTotal())) {
      problems.add(
          "Enter what actually came off the account, in "
              + header.fundingCurrencyCode()
              + ", before confirming — this receipt is billed in "
              + currency
              + ".");
    }
    if (header.neitherIsBase() && isZero(header.baseTotal())) {
      problems.add(
          "Enter the "
              + header.baseCurrencyCode()
              + " amount before confirming — neither "
              + currency
              + " nor "
              + header.fundingCurrencyCode()
              + " is the book's base currency, so the conversion has nothing to balance against.");
    }
  }

  /** A header total that is blank, unparseable, or an explicit zero — all of them a hard block. */
  private static boolean isZero(String total) {
    return ReceiptEditorText.parse(total).signum() == 0;
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
  private void checkLines(ReceiptEditorForm form, String lineCurrency, List<String> problems) {
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
        checkLine(lines.get(i), i + 1, lineCurrency, postable, problems);
      }
    }
  }

  private void checkLine(
      WorkingLine line,
      int number,
      String lineCurrency,
      Set<Long> postable,
      List<String> problems) {
    if (!line.personName().isBlank()) {
      return; // a beneficiary line: the person's debt leaf is provisioned at commit
    }
    if (line.categoryId().isBlank()) {
      problems.add("Line " + number + describe(line) + " has no category yet — pick one.");
      return;
    }
    if (!line.transferDirection().isBlank()) {
      // A transfer to a real own account: not a category, so not subdivisible — but its currency
      // is fixed by the account, and the entry spans at most the two currencies the header names.
      checkTransferCurrency(line, number, lineCurrency, problems);
      return;
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

  /**
   * A transfer line's target must be denominated in the line currency — the receipt's own currency
   * (issue receipts/23). One receipt is one merchant billing one currency paid from one account at
   * one rate, so the header fixes at most two currencies and a third-currency transfer leg has no
   * rate to be expressed at; {@code DockSplitService.resolveLine} refuses it outright, and this
   * says so before Confirm rather than after.
   */
  private void checkTransferCurrency(
      WorkingLine line, int number, String lineCurrency, List<String> problems) {
    Long targetId = ReceiptEditorText.parseId(line.categoryId());
    if (targetId == null || lineCurrency == null || lineCurrency.isBlank()) {
      return;
    }
    accountService
        .findById(targetId)
        .filter(target -> !lineCurrency.equals(target.currencyCode()))
        .ifPresent(
            target ->
                problems.add(
                    "Line "
                        + number
                        + describe(line)
                        + " transfers to "
                        + target.name()
                        + ", which is a "
                        + target.currencyCode()
                        + " account — on a "
                        + lineCurrency
                        + " receipt every transfer line must target a "
                        + lineCurrency
                        + " account. A receipt mixing more currencies is two transactions."));
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
