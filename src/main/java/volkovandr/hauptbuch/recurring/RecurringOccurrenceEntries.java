package volkovandr.hauptbuch.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.operations.SplitCurrencyService;
import volkovandr.hauptbuch.operations.SplitEntry;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
import volkovandr.hauptbuch.operations.SplitLineDraft;
import volkovandr.hauptbuch.operations.SplitTotals;
import volkovandr.hauptbuch.operations.SplitTotalsQuery;
import volkovandr.hauptbuch.recurring.repository.RecurringTemplateRepository;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The entry one occurrence of a template books (data-model §14.1/§14.3): the stored split shape,
 * back in the dock's {@link SplitEntry} on the occurrence date, for {@code DockSplitService} to
 * resolve into postings exactly as it resolves a hand-typed split. People are named by their stored
 * ids, since live names can repeat. A cross-currency entry's funding and base totals are not
 * stored: they are proposed from the latest rate on or before the occurrence date.
 */
@Component
class RecurringOccurrenceEntries {

  private static final int FRACTION_DIGITS = 2;

  private final RecurringTemplateRepository repository;
  private final AccountService accountService;
  private final SplitCurrencyService splitCurrencyService;

  RecurringOccurrenceEntries(
      RecurringTemplateRepository repository,
      AccountService accountService,
      SplitCurrencyService splitCurrencyService) {
    this.repository = repository;
    this.accountService = accountService;
    this.splitCurrencyService = splitCurrencyService;
  }

  /**
   * The entry {@code template} books for its occurrence on {@code occurrenceDate}: {@code
   * confirmed} for an {@code auto} template, {@code pending_review} for a {@code review} one.
   */
  SplitEntry entryFor(RecurringTemplate template, LocalDate occurrenceDate) {
    List<SplitLineDraft> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (RecurringTemplateLine line : repository.findLines(template.recurringTemplateId())) {
      String amount = SplitLineAmounts.formatSignedAmount(line.amount());
      lines.add(
          new SplitLineDraft(
              line.accountId(),
              amount,
              line.note(),
              line.transferDirection(),
              null,
              line.personDirection(),
              null,
              line.personId(),
              repository.findLineTagIds(line.recurringTemplateLineId())));
      net = net.add(contribution(line, amount));
    }
    // The spending total the panel would show is the lines' net; the proposal converts it at the
    // occurrence date's rate, and hands blank totals back for a single-currency entry.
    SplitTotals totals =
        splitCurrencyService.proposeTotals(
            new SplitTotalsQuery(
                template.accountId(),
                template.spendingCurrencyCode(),
                occurrenceDate,
                MoneyFormat.number(net.abs(), FRACTION_DIGITS),
                null,
                null));
    return new SplitEntry(
        null,
        occurrenceDate,
        template.accountId(),
        null,
        template.fundingPersonDirection(),
        null,
        template.personId(),
        template.payeeId(),
        null,
        template.note(),
        template.spendingCurrencyCode(),
        totals.fundingTotal(),
        totals.baseTotal(),
        repository.findTagIds(template.recurringTemplateId()),
        lines,
        RecurringScheduleForm.REVIEW.equals(template.confirmation())
            ? "pending_review"
            : "confirmed");
  }

  /** A stored line's signed contribution to the funding leg, as the split panel computes it. */
  private BigDecimal contribution(RecurringTemplateLine line, String amount) {
    if (line.personId() != null || line.transferDirection() != null) {
      return SplitLineAmounts.lenientContribution(
          amount, null, line.transferDirection(), line.personDirection());
    }
    String type = accountService.findById(line.accountId()).map(Account::type).orElse(null);
    return SplitLineAmounts.lenientContribution(amount, type, null, null);
  }
}
