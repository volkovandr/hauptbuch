package volkovandr.hauptbuch.ledger;

import java.util.List;
import volkovandr.hauptbuch.accounts.AccountEntryLabel;

/**
 * The whole register screen as one immutable view model (register §2) — the rows to render plus the
 * state the filter form needs to show what is active. Assembled by {@link RegisterService} so the
 * controller stays thin and the template does no computation beyond iteration and formatting.
 *
 * @param rows the register rows, oldest-first (newest at the bottom — register §2.1)
 * @param accounts every real account offered in the account multi-select, in list order
 * @param people the per-person debt leaves offered in the same multi-select, listed as {@code Name
 *     (CUR)} (register §2.6, plan stage 8c) — individually selectable and combinable with the real
 *     accounts. Kept a separate list from {@link #accounts} because a person is reached in the
 *     <em>entry</em> dock only by the {@code for}/{@code by} sigils, never by picking their leaf —
 *     so people belong in the filter but must stay out of the dock's Account datalist
 * @param payees every payee offered in the payee filter, alphabetical
 * @param transferTargets the {@code To → <account>} / {@code From ← <account>} values the Category
 *     datalist also offers, routing the counter-leg to a real account (register §3.5, plan stage
 *     7d.3)
 * @param personTargets the {@code for <person>} / {@code by <person>} values the Category datalist
 *     also offers, routing the counter-leg to that person's per-currency debt leaf (register §3.5,
 *     plan stage 8b, data-model §7)
 * @param tagOptions the canonical {@code Parent:Child} labels of every live tag, the dock's tag
 *     chip datalist suggestions (register §3.6, plan stage 7e)
 * @param filter the currently-applied filter (selected accounts, date range, payee), for redisplay
 */
public record RegisterView(
    List<RegisterRowView> rows,
    List<RegisterAccountOption> accounts,
    List<RegisterAccountOption> people,
    List<RegisterPayeeOption> payees,
    List<RegisterCategoryOption> categories,
    List<String> transferTargets,
    List<String> personTargets,
    List<String> tagOptions,
    RegisterFilter filter) {

  /** Defensively copy the row and option lists to immutable lists. */
  public RegisterView {
    rows = List.copyOf(rows);
    accounts = List.copyOf(accounts);
    people = List.copyOf(people);
    payees = List.copyOf(payees);
    categories = List.copyOf(categories);
    transferTargets = List.copyOf(transferTargets);
    personTargets = List.copyOf(personTargets);
    tagOptions = List.copyOf(tagOptions);
  }

  /**
   * The account a fresh dock pre-fills its Account field with, and the id it pre-resolves to
   * (register §3.3, plan stage 8b.1). Because the field is free text rather than a {@code
   * <select>}, the dock must render a value <em>and</em> its resolved id together, or accepting the
   * default without typing would commit nothing — the regression risk that kept the field a {@code
   * <select>} until the field conversion. Empty when the book has no accounts yet.
   */
  public String defaultAccountEntryText() {
    return accounts.isEmpty() ? "" : accounts.get(0).entryValue();
  }

  /**
   * The id matching {@link #defaultAccountEntryText()}; {@code null} when there are no accounts.
   */
  public Long defaultAccountId() {
    return accounts.isEmpty() ? null : accounts.get(0).accountId();
  }

  /**
   * One account offered in the register's account multi-select (register §2.3), carrying whether it
   * is currently selected so the form redisplays the active choice.
   *
   * @param accountId the account
   * @param name display name
   * @param hue stored register hue (for the swatch); null on accounts with no thread colour
   * @param currencyCode the account's currency
   * @param selected whether this account is in the applied filter
   */
  public record RegisterAccountOption(
      long accountId, String name, Integer hue, String currencyCode, boolean selected) {

    /**
     * The {@code Name (CUR)} value the dock's Account datalist offers and its resolver round-trips
     * back to this account (register §3.3, plan stage 8b.1) — the same shape {@link
     * RegisterPayeeOption#entryValue()} plays for payees. Delegates to {@link AccountEntryLabel},
     * which owns both this format and the parser that reads it back, so the label the picker offers
     * and the label the resolver parses cannot drift apart.
     */
    public String entryValue() {
      return AccountEntryLabel.format(name, currencyCode);
    }
  }

  /**
   * One payee offered in the register's pickers, carrying whether it is the active filter choice.
   *
   * @param payeeId the payee
   * @param name the {@code Name · City · Country} display label (distinguishes same-named payees —
   *     register §3.4)
   * @param entryValue the {@code Name - City - Country} value the dock datalist offers, which the
   *     create-new parser round-trips back to this payee (so re-picking reuses it)
   * @param selected whether this payee is the applied filter
   */
  public record RegisterPayeeOption(
      long payeeId, String name, String entryValue, boolean selected) {}

  /**
   * One category offered in the dock's category datalist (register §3.5) — the semantic categories
   * a new transaction can be filed under. The dock resolves the picked name back to an id (or
   * creates a {@code Parent - Child}) through the {@code categories} module.
   *
   * @param accountId the category account
   * @param name display name
   */
  public record RegisterCategoryOption(long accountId, String name) {}
}
