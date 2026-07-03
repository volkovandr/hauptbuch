package volkovandr.hauptbuch.accounts;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An account row (data-model §3.2). One representation backs real accounts, categories
 * (income/expense), and per-person debts — the {@code type} and the presence of an owner link
 * distinguish them. Every account has exactly one currency; postings inherit it, so a posting
 * stores no currency of its own.
 *
 * <p>Born in {@code ledger} at stage 3 (the engine needed to read accounts before this module
 * existed); stage 6a moves ownership here so the concept lives in one module — {@code ledger} now
 * depends on this public type (plan stage 6).
 *
 * <p>Hierarchy is via {@code parentId} (NULL = top level). Posting is leaves-only (data-model §5):
 * the engine posts only to accounts that are not themselves a parent. This is upheld in the service
 * layer and verified by the SQL-logic suite, not by a DB constraint.
 *
 * @param accountId surrogate PK; null for a not-yet-persisted account
 * @param name display name
 * @param type one of {@code asset | liability | income | expense | equity}
 * @param parentId self-reference to the parent account; null at top level
 * @param currencyCode ISO-4217 code of this account's single currency
 * @param hue stored two-tone register hue, degrees on the HSL wheel (register §2.8); null on system
 *     and category-backing accounts, which are never register threads
 * @param openedAt date the account was opened; nullable
 * @param closedAt date the account was closed; nullable
 * @param deletedAt soft-delete timestamp; null while live
 */
public record Account(
    Long accountId,
    String name,
    String type,
    Long parentId,
    String currencyCode,
    Integer hue,
    LocalDate openedAt,
    LocalDate closedAt,
    OffsetDateTime deletedAt) {}
