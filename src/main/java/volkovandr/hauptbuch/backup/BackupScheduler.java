package volkovandr.hauptbuch.backup;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * The unattended half of the feature: a daily backup, and a catch-up at boot.
 *
 * <p>The catch-up exists because the Pi is not always up at the scheduled hour — an overnight
 * reboot or a power cut would otherwise silently cost a day. It fires only when today's scheduled
 * time has <em>already passed</em> and no backup exists for today, so restarting at 01:00 with a
 * 03:00 schedule does not produce a backup now and a second one two hours later. A day already
 * covered by a manual backup does not get one either.
 *
 * <p>Registered only when {@code hauptbuch.backup.automatic} is true. That is off in the packaged
 * defaults and switched on by the Pi's config: a dev machine and — more to the point — the test
 * suites, which boot the full context repeatedly, have no business shelling out to {@code pg_dump}.
 *
 * <p>Neither path propagates a failure, and that means <em>any</em> runtime failure, not just
 * {@link BackupFailedException}: reading or sweeping the directory throws {@code
 * UncheckedIOException} if the card has gone read-only, and letting that escape the {@link
 * ApplicationReadyEvent} listener would abort startup — leaving the app refusing to boot at exactly
 * the moment its operator most needs the ledger. A WARN is the right level for an expected failure
 * that was handled (CLAUDE.md §5); the screen still tells the truth, since the listing simply has
 * no entry for that day.
 */
@Component
@ConditionalOnProperty(name = "hauptbuch.backup.automatic", havingValue = "true")
class BackupScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(BackupScheduler.class);

  private final BackupService backupService;
  private final BackupProperties properties;

  BackupScheduler(BackupService backupService, BackupProperties properties) {
    this.backupService = backupService;
    this.properties = properties;
  }

  /** The daily backup, which also sweeps automatic backups beyond the keep count. */
  @Scheduled(cron = "${hauptbuch.backup.daily-cron}")
  void takeDailyBackup() {
    take("Scheduled backup failed");
  }

  /** On boot, take today's backup if the app was not running when it was due. */
  @EventListener(ApplicationReadyEvent.class)
  void catchUpOnStartup() {
    catchUpAt(LocalDateTime.now());
  }

  /** The catch-up decision against an explicit "now" (package-visible so it can be tested). */
  // PMD.AvoidCatchingGenericException: deliberate. This is the outermost frame of an unattended
  // job, and the whole point (see the class note) is that nothing escapes it into application
  // startup. Catching only BackupFailedException is exactly the bug this guards against.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  void catchUpAt(LocalDateTime now) {
    try {
      if (!alreadyDueToday(now) || backupService.hasBackupOn(now.toLocalDate())) {
        return;
      }
      LOG.info("Today's scheduled backup was missed; taking it now");
      backupService.take(BackupKind.AUTOMATIC);
    } catch (RuntimeException e) {
      LOG.warn("Startup catch-up backup failed: {}", e.getMessage());
    }
  }

  /** Whether today's first scheduled time is at or before {@code now}. */
  private boolean alreadyDueToday(LocalDateTime now) {
    LocalDate today = now.toLocalDate();
    LocalDateTime firstToday =
        CronExpression.parse(properties.dailyCron()).next(today.atStartOfDay().minusNanos(1));
    return firstToday != null && firstToday.toLocalDate().equals(today) && !firstToday.isAfter(now);
  }

  // PMD.AvoidCatchingGenericException: as above — the scheduler thread is shared with the receipt
  // batch poller, so an escaping runtime exception would take that down too.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void take(String failureMessage) {
    try {
      backupService.take(BackupKind.AUTOMATIC);
    } catch (RuntimeException e) {
      LOG.warn("{}: {}", failureMessage, e.getMessage());
    }
  }
}
