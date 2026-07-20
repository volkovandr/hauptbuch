package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import java.util.List;

/**
 * The register entry dock's form fields, bound in one shot (register §3, plan stage 7b/7d.1) — the
 * dock's own inputs plus the active-filter {@code view*} hidden fields it carries so the commit can
 * repaint exactly the current view (§2.2). Spring MVC binds the POST body to this record; keeping
 * the fields together (rather than a long parameter list on the controller method) is the readable
 * shape.
 *
 * @param transactionId the transaction being edited; {@code null} for a new entry (register §3.1)
 * @param date booking date
 * @param accountId the funding account the Account field resolved to; {@code null} when the field
 *     instead named a person (below), whose leaf does not exist until commit
 * @param fundingPersonName the <em>funding</em> person's name when the Account field's {@code
 *     for}/{@code by} resolver matched a person (register §3.3, plan stage 8b.1) — "Max paid for a
 *     pure expense of mine"; {@code null}/blank for an ordinary account
 * @param fundingPersonDirection {@code FOR}/{@code BY} alongside {@code fundingPersonName}
 * @param fundingPersonRevive the revival decision for {@code fundingPersonName}, exactly as {@code
 *     personRevive} carries it for the counterpart
 * @param payeeText the payee text (a picked datalist value or a create-new string); nullable
 * @param amount the funding leg's sign-free magnitude with an optional leading {@code +}/{@code −}
 *     (§3.8)
 * @param categoryId the category id the {@code categories} resolve step produced
 * @param categoryCurrencyCode the (possibly overridden) leaf currency; null/blank defaults to the
 *     funding account's currency (§3.5)
 * @param categoryAmount the category leg's own native magnitude; present only when cross-currency
 *     (§3.8a)
 * @param baseAmount the frozen base-currency magnitude; present only when neither leg is base
 * @param note transaction note; nullable
 * @param transferDirection {@code TO}/{@code FROM} when the counterpart resolver matched a transfer
 *     target (register §3.8, plan stage 7d.3); {@code null}/blank for a category counterpart
 * @param personName the counterpart person's name when the {@code for}/{@code by} resolver matched
 *     a person target (register §3.5, plan stage 8b); {@code null}/blank otherwise
 * @param personDirection {@code FOR}/{@code BY} alongside {@code personName} (data-model §7);
 *     {@code null}/blank otherwise
 * @param personRevive whether a soft-deleted-only name match should be revived ({@code "true"}) or
 *     a distinct new person created instead ({@code "false"}); {@code null}/blank when the name had
 *     no soft-deleted-only match to decide on
 * @param tagId the resolved tag ids of the committed chips (register §3.6, plan stage 7e) — one
 *     hidden input per pill, bound here as a list; empty when the transaction carries no tags
 * @param viewAccountId the active filter's viewed accounts; empty for the default set
 * @param viewFromDate the active filter's lower date bound; nullable
 * @param viewToDate the active filter's upper date bound; nullable
 * @param viewPayeeId the active filter's payee; nullable
 */
public record DockEntryForm(
    Long transactionId,
    LocalDate date,
    Long accountId,
    String fundingPersonName,
    String fundingPersonDirection,
    String fundingPersonRevive,
    String payeeText,
    String amount,
    Long categoryId,
    String categoryCurrencyCode,
    String categoryAmount,
    String baseAmount,
    String note,
    String transferDirection,
    String personName,
    String personDirection,
    String personRevive,
    List<Long> tagId,
    List<Long> viewAccountId,
    LocalDate viewFromDate,
    LocalDate viewToDate,
    Long viewPayeeId) {

  /** Defensively copy the list fields (null-safe) so the record cannot be mutated after. */
  public DockEntryForm {
    tagId = tagId == null ? null : List.copyOf(tagId);
    viewAccountId = viewAccountId == null ? null : List.copyOf(viewAccountId);
  }

  /**
   * Whether the Account field named a person rather than an account (register §3.3, plan stage
   * 8b.1) — the funding leg is then a debt leaf provisioned at commit, so there is no {@code
   * accountId} to read.
   */
  public boolean hasFundingPerson() {
    return isPresent(fundingPersonName) && isPresent(fundingPersonDirection);
  }

  private static boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
