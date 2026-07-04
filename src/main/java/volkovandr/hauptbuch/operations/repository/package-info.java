/**
 * Persistence internals of the {@code operations} module — native-SQL repositories (JdbcClient +
 * records, no ORM; CLAUDE.md §1.3).
 *
 * <p>This is a <em>sub-package of</em> {@code operations}, so Spring Modulith treats it as
 * <strong>module internal</strong>: only {@code operations}'s own root-package types form the
 * module's public API, and {@code ApplicationModules.verify()} forbids any other module from
 * touching these repositories. The classes are {@code public} purely so the module's root-package
 * types ({@code SubdivisionService}) can call them across this package boundary — that visibility
 * does <em>not</em> open them to other modules, which is exactly the api/internal idiom (CLAUDE.md
 * §1.1).
 */
package volkovandr.hauptbuch.operations.repository;
