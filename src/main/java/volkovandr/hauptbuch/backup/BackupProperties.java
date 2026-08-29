package volkovandr.hauptbuch.backup;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where backups live and how many are kept.
 *
 * <p>On the Pi the root sits beside the receipt images under the app's data directory, which the
 * systemd unit already lists in {@code ReadWritePaths} — so no unit change is needed to start
 * writing dumps. In dev it is a throwaway local directory, as receipt storage is.
 *
 * @param storageRoot the directory dumps are written to; created on first use, owner-only
 * @param keepAutomatic how many automatic backups to keep; older ones are swept after each
 *     scheduled run. Manual backups are never swept, whatever this is set to
 * @param automatic whether the scheduled daily backup and its startup catch-up run at all. Off by
 *     default — a dev machine and the test suites have no business writing dumps; the Pi's config
 *     turns it on
 * @param dailyCron the Spring cron expression for the daily backup
 */
@ConfigurationProperties("hauptbuch.backup")
public record BackupProperties(
    Path storageRoot, int keepAutomatic, boolean automatic, String dailyCron) {}
