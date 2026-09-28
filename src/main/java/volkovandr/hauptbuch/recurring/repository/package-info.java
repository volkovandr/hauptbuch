/**
 * Persistence internals of the {@code recurring} module — native-SQL repositories (JdbcClient +
 * records, no ORM; CLAUDE.md §1.3).
 *
 * <p>A sub-package, so Spring Modulith treats it as module internal: the classes are {@code public}
 * only so {@code recurring}'s root-package services can call them, and {@code
 * ApplicationModules.verify()} still forbids other modules from reaching in.
 */
package volkovandr.hauptbuch.recurring.repository;
