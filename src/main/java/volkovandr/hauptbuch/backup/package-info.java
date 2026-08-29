/**
 * Backup module — database backups to file, on demand and on a schedule.
 *
 * <p>A Spring Modulith application module. Other modules may depend only on this package's public
 * top-level types; sub-packages are internal (see CLAUDE.md §1.1).
 *
 * <p>Backups are {@code pg_dump} custom-format files in a configured directory. There is
 * deliberately no {@code backup} table and no migration: a table listing backups would be inside
 * every dump, so restoring an old backup would restore a stale list of backups. The filesystem is
 * the record, and the filename carries the kind and timestamp.
 *
 * <p>Restore is <em>not</em> in this module, or anywhere in the app. It has to drop and recreate
 * the live database, which the app cannot do to its own open connection, and the systemd unit
 * denies it the privilege to stop and start itself; it is a documented manual procedure, with the
 * screen offering a download and the exact commands to copy.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Backup")
package volkovandr.hauptbuch.backup;
