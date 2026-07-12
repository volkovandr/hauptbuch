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
 * @param accountId the funding account
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
 * @param viewAccountId the active filter's viewed accounts; empty for the default set
 * @param viewFromDate the active filter's lower date bound; nullable
 * @param viewToDate the active filter's upper date bound; nullable
 * @param viewPayeeId the active filter's payee; nullable
 */
public record DockEntryForm(
    Long transactionId,
    LocalDate date,
    Long accountId,
    String payeeText,
    String amount,
    Long categoryId,
    String categoryCurrencyCode,
    String categoryAmount,
    String baseAmount,
    String note,
    List<Long> viewAccountId,
    LocalDate viewFromDate,
    LocalDate viewToDate,
    Long viewPayeeId) {

  /** Defensively copy the viewed-account list (null-safe) so the record cannot be mutated after. */
  public DockEntryForm {
    viewAccountId = viewAccountId == null ? null : List.copyOf(viewAccountId);
  }
}
