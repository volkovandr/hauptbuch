package volkovandr.hauptbuch.statements;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsQuery;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsService;
import volkovandr.hauptbuch.statements.repository.OriginalCharge;
import volkovandr.hauptbuch.statements.repository.StatementLineRepository;

/**
 * The counterpart amounts the statement dock asks for when a missing line is a transfer to an
 * account in another currency (statements.md §6.4): the bank only knows its own side, so the other
 * side's native amount is proposed from the rate feed for the operator to keep or overwrite. A line
 * that carries a foreign charge (issue statements/12) asks the same fields for a category, filled
 * from the charge the bank printed.
 */
@Service
class StatementCrossCurrencyService {

  private final StatementService statementService;
  private final StatementMatchService matchService;
  private final AccountService accountService;
  private final CrossCurrencyFieldsService crossCurrencyFieldsService;
  private final StatementLineRepository lineRepository;

  StatementCrossCurrencyService(
      StatementService statementService,
      StatementMatchService matchService,
      AccountService accountService,
      CrossCurrencyFieldsService crossCurrencyFieldsService,
      StatementLineRepository lineRepository) {
    this.statementService = statementService;
    this.matchService = matchService;
    this.accountService = accountService;
    this.crossCurrencyFieldsService = crossCurrencyFieldsService;
    this.lineRepository = lineRepository;
  }

  /**
   * The extra fields for the line's dock, or empty when none are needed: a transfer into another
   * currency asks for the counterpart's amount; anything else on a line with a foreign charge asks
   * for the charge's amount, picked or not.
   *
   * @param target the resolved category or transfer-target account id, or null
   * @param transferDirection {@code TO}/{@code FROM} for a transfer target, else null or blank
   * @param date the transaction date the rates are looked up as of; may be null
   */
  Optional<CrossCurrencyView> forTarget(
      long statementId,
      long statementLineId,
      Long target,
      String transferDirection,
      LocalDate date) {
    boolean transfer = transferDirection != null && !transferDirection.isBlank();
    Optional<OriginalCharge> charge = lineRepository.findOriginalCharge(statementLineId);
    if (!transfer) {
      // Not a transfer (a category, or nothing picked yet): the charge's fields, if it has one.
      return charge.flatMap(c -> view(statementId, statementLineId, c.currencyCode(), c, date));
    }
    if (target == null) {
      return Optional.empty();
    }
    Optional<Account> targetAccount = accountService.findById(target);
    return targetAccount.flatMap(
        t -> view(statementId, statementLineId, t.currencyCode(), charge.orElse(null), date));
  }

  /**
   * The dock input with the foreign charge's fields set, when the line carries one; otherwise the
   * input unchanged.
   */
  DockInput withForeignCharge(
      DockInput input, long statementId, long statementLineId, LocalDate date) {
    return forTarget(statementId, statementLineId, input.categoryId(), null, date)
        .map(
            v ->
                input.withCrossCurrency(
                    v.currencyCode(),
                    v.counterpartAmount(),
                    v.showBase() ? DockForms.orEmpty(v.baseAmount()) : null))
        .orElse(input);
  }

  /** The fields toward {@code currency}, or empty when it is the paying account's own. */
  private Optional<CrossCurrencyView> view(
      long statementId,
      long statementLineId,
      String currency,
      OriginalCharge charge,
      LocalDate date) {
    Optional<Account> funding =
        accountService.findById(statementService.get(statementId).accountId());
    if (funding.isEmpty() || funding.get().currencyCode().equals(currency)) {
      return Optional.empty();
    }
    String fundingCurrency = funding.get().currencyCode();
    String bankAmount =
        StatementController.number(line(statementId, statementLineId).amount().abs());
    CrossCurrencyFields fields =
        crossCurrencyFieldsService.resolve(
            new CrossCurrencyFieldsQuery(fundingCurrency, currency, date, bankAmount, null, null));
    boolean printed = charge != null && currency.equals(charge.currencyCode());
    return Optional.of(
        new CrossCurrencyView(
            currency,
            printed
                ? StatementController.number(charge.amount().abs())
                : crossCurrencyFieldsService.prefillFundingTotal(
                    currency, fundingCurrency, date, bankAmount),
            fields.neitherIsBase(),
            fields.baseAmountText()));
  }

  private StatementLine line(long statementId, long statementLineId) {
    return matchService.lineOf(statementId, statementLineId).line();
  }

  /**
   * What the dock shows beside the bank's amount.
   *
   * @param currencyCode the counterpart account's currency
   * @param counterpartAmount the proposed native amount there, or null with no rate on file
   * @param showBase whether a base amount is asked for too (neither account is the base currency)
   * @param baseAmount the proposed base amount, or null
   */
  record CrossCurrencyView(
      String currencyCode, String counterpartAmount, boolean showBase, String baseAmount) {}
}
