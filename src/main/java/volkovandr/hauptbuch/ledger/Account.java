package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An account row (data-model §3.2). One representation backs real accounts, categories
 * (income/expense), and per-person debts — the {@code type} and the presence of an owner link
 * distinguish them. Every account has exactly one currency; postings inherit it, so a posting
 * stores no currency of its own.
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
    LocalDate openedAt,
    LocalDate closedAt,
    OffsetDateTime deletedAt) {}
