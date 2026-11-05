package volkovandr.hauptbuch.statements;

/**
 * An account that has at least one live statement: one option of the Statements list's account
 * filter.
 *
 * @param accountId the account
 * @param name the account's name
 */
public record StatementAccount(long accountId, String name) {}
