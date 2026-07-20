package volkovandr.hauptbuch.operations;

/**
 * What the dock's Account field resolved to (register §3.3, plan stage 8b.1) — exactly one of: a
 * real own account, a funding <em>person</em>, a pending revival choice, or an error. Flat rather
 * than sealed because the template renders all four branches from one fragment, and Thymeleaf reads
 * plain accessors far more readably than it pattern-matches.
 *
 * @param accountId the resolved own account; {@code null} in every other case
 * @param personName the funding person's name (register §2.6 pattern 3); {@code null} otherwise
 * @param personDirection {@code FOR}/{@code BY} alongside {@code personName}
 * @param personRevive {@code "true"}/{@code "false"} once a soft-deleted-only name has been decided
 * @param statusText the caption shown beside the field on a successful resolve
 * @param error the user-facing message when nothing resolved; {@code null} on success
 * @param pending whether a Restore/Create-new choice is being asked for
 * @param pendingName the name that choice is about
 */
record DockAccountResolution(
    Long accountId,
    String personName,
    String personDirection,
    String personRevive,
    String statusText,
    String error,
    boolean pending,
    String pendingName) {

  static DockAccountResolution account(long accountId, String statusText) {
    return new DockAccountResolution(accountId, null, null, null, statusText, null, false, null);
  }

  static DockAccountResolution person(
      String personName, String personDirection, Boolean revive, String statusText) {
    return new DockAccountResolution(
        null,
        personName,
        personDirection,
        revive == null ? null : String.valueOf(revive),
        statusText,
        null,
        false,
        null);
  }

  static DockAccountResolution pendingRevival(String personName) {
    return new DockAccountResolution(null, null, null, null, null, null, true, personName);
  }

  static DockAccountResolution error(String message) {
    return new DockAccountResolution(null, null, null, null, null, message, false, null);
  }
}
