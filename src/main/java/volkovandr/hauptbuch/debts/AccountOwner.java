package volkovandr.hauptbuch.debts;

/**
 * The junction that links a per-person debt account to the {@link Person} who "owns" it (data-model
 * §3.3). One owner per account; many accounts per person (one per currency: Max-EUR, Max-CHF).
 *
 * @param accountOwnerId surrogate PK; null for a not-yet-persisted link
 * @param accountId the account being owned (a per-person `asset` leaf)
 * @param personId the person who owns it
 */
public record AccountOwner(Long accountOwnerId, Long accountId, Long personId) {}
