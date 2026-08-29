package volkovandr.hauptbuch.backup;

/**
 * How a backup came to exist — the one distinction retention cares about.
 *
 * <p>An {@link #AUTOMATIC} backup is one of a rolling series the scheduler produces and sweeps; a
 * {@link #MANUAL} one was taken deliberately, immediately before something risky, and is therefore
 * never swept. The kind is carried in the filename (see {@link BackupNames}), because the
 * filesystem is the only record of a backup's existence.
 */
public enum BackupKind {

  /** Taken by hand from the backup screen. Never deleted by retention. */
  MANUAL("manual"),

  /** Taken by the scheduler. Subject to the "keep the newest N" sweep. */
  AUTOMATIC("auto");

  private final String token;

  BackupKind(String token) {
    this.token = token;
  }

  /** The token this kind occupies in a backup filename. */
  String suffix() {
    return token;
  }

  /** Human label for the listing. */
  public String label() {
    return this == MANUAL ? "Manual" : "Automatic";
  }
}
