/**
 * Persistence internals of the {@code analytics} module — native-SQL repositories (JdbcClient +
 * records, no ORM; CLAUDE.md §1.3).
 *
 * <p>This is a <em>sub-package of</em> {@code analytics}, so Spring Modulith treats it as
 * <strong>module internal</strong>: only {@code analytics}'s own root-package types form the
 * module's public API, and {@code ApplicationModules.verify()} forbids any other module from
 * touching these repositories. The classes are {@code public} purely so the module's root-package
 * services ({@code ReportEngine}) can call them across this package boundary.
 */
package volkovandr.hauptbuch.analytics.repository;
