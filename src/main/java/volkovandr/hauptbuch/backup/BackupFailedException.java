package volkovandr.hauptbuch.backup;

/**
 * A backup could not be produced — {@code pg_dump} is missing, refused the connection, or exited
 * non-zero, or the datasource is not a PostgreSQL one.
 *
 * <p>An <em>expected</em> failure that the screen handles: the controller catches it and re-renders
 * with the message, so a broken backup is visible rather than a 500 (CLAUDE.md §5 puts this at
 * WARN, not ERROR). Its message is shown to the user, so it must never carry a credential.
 */
public class BackupFailedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** A failure with a user-facing explanation. */
  public BackupFailedException(String message) {
    super(message);
  }

  /** A failure with a user-facing explanation and the underlying cause. */
  public BackupFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
