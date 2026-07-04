package volkovandr.hauptbuch.operations;

import volkovandr.hauptbuch.accounts.Account;

/**
 * The outcome of {@link SubdivisionService#subdivideAccount}: the newly-created child, and — only
 * when the parent leaf carried existing postings — the catch-all sibling that absorbed them.
 *
 * @param child the account created under the requested name
 * @param catchAll the catch-all sibling created to absorb the former leaf's postings; {@code null}
 *     when the parent had none to reassign (no pointless empty catch-all)
 */
public record SubdivisionResult(Account child, Account catchAll) {}
