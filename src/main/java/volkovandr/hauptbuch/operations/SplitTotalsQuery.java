package volkovandr.hauptbuch.operations;

import java.time.LocalDate;

/**
 * The inputs to {@link SplitCurrencyService#proposeTotals} (issue receipts/23) — the header as the
 * operator has it so far, plus what the rate lookup needs. Bundled for the same reason {@link
 * SplitCurrencyQuery} is.
 *
 * @param accountId the paying account, whose currency fixes the funding leg; null means no account
 *     (a person funds the entry, which is single-currency by construction) and nothing is proposed
 * @param spendingCurrencyCode the currency the entry is billed in
 * @param date the transaction date, to look the rates up as of it; may be null
 * @param total the spending-currency total as typed — what a blank funding total is proposed from
 * @param fundingTotal the funding-currency total as typed; blank invites a proposal
 * @param baseTotal the base-currency total as typed; blank invites a proposal
 */
public record SplitTotalsQuery(
    Long accountId,
    String spendingCurrencyCode,
    LocalDate date,
    String total,
    String fundingTotal,
    String baseTotal) {}
