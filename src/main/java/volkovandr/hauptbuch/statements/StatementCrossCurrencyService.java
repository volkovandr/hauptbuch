package volkovandr.hauptbuch.statements;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsQuery;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsService;

/**
 * The counterpart amounts the statement dock asks for when a missing line is a transfer to an
 * account in another currency (statements.md §6.4): the bank only knows its own side, so the other
 * side's native amount is proposed from the rate feed for the operator to keep or overwrite.
 */
@Service
class StatementCrossCurrencyService {

  private final StatementService statementService;
  private final StatementMatchService matchService;
  private final AccountService accountService;
  private final CrossCurrencyFieldsService crossCurrencyFieldsService;

  StatementCrossCurrencyService(
      StatementService statementService,
      StatementMatchService matchService,
      AccountService accountService,
      CrossCurrencyFieldsService crossCurrencyFieldsService) {
    this.statementService = statementService;
    this.matchService = matchService;
    this.accountService = accountService;
    this.crossCurrencyFieldsService = crossCurrencyFieldsService;
  }

  /**
   * The extra fields for the line's dock, or empty when the entry is not a transfer into another
   * currency (a category or person follows the paying account's currency).
   *
   * @param transferTarget the resolved target account id, or null
   * @param transferDirection {@code TO}/{@code FROM} for a transfer target, else null or blank
   * @param date the transaction date the rates are looked up as of; may be null
   */
  Optional<CrossCurrencyView> forTransfer(
      long statementId,
      long statementLineId,
      Long transferTarget,
      String transferDirection,
      LocalDate date) {
    if (transferTarget == null || transferDirection == null || transferDirection.isBlank()) {
      return Optional.empty();
    }
    Optional<Account> target = accountService.findById(transferTarget);
    Optional<Account> funding =
        accountService.findById(statementService.get(statementId).accountId());
    if (target.isEmpty() || funding.isEmpty()) {
      return Optional.empty();
    }
    String fundingCurrency = funding.get().currencyCode();
    String targetCurrency = target.get().currencyCode();
    if (fundingCurrency.equals(targetCurrency)) {
      return Optional.empty();
    }
    String bankAmount =
        StatementController.number(line(statementId, statementLineId).amount().abs());
    CrossCurrencyFields fields =
        crossCurrencyFieldsService.resolve(
            new CrossCurrencyFieldsQuery(
                fundingCurrency, targetCurrency, date, bankAmount, null, null));
    return Optional.of(
        new CrossCurrencyView(
            targetCurrency,
            crossCurrencyFieldsService.prefillFundingTotal(
                targetCurrency, fundingCurrency, date, bankAmount),
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
