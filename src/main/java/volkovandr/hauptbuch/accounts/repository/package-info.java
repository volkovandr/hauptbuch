/**
 * Persistence internals of the {@code accounts} module — native-SQL repositories (JdbcClient +
 * records, no ORM; CLAUDE.md §1.3).
 *
 * <p>This is a <em>sub-package of</em> {@code accounts}, so Spring Modulith treats it as
 * <strong>module internal</strong>: only {@code accounts}'s own root-package types (its named
 * interface) form the module's public API, and {@code ApplicationModules.verify()} forbids any
 * other module from touching these repositories. The classes are {@code public} purely so the
 * module's root-package types ({@code AccountService}, {@code AccountsController}) can call them
 * across this package boundary — that visibility does <em>not</em> open them to other modules,
 * which is exactly the api/internal idiom.
 */
package volkovandr.hauptbuch.accounts.repository;
