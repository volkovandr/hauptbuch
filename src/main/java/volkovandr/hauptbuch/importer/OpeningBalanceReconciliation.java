package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The opening-balance winner proposal (import.md §5.1; plan c3) — pure logic, no DB. Given Money's
 * staged opening balance for an account and the target Hauptbuch account's existing one, proposes
 * which to keep: <strong>the earlier-dated one, ties broken toward the non-zero one</strong>. The
 * owner sees both and overrides, so this is only the default the map row starts on.
 *
 * <p>This is the <em>only</em> conflict the account map raises; overlapping ordinary transactions
 * are handled once, at commit, by the duplicate scan (§9).
 */
final class OpeningBalanceReconciliation {

  private OpeningBalanceReconciliation() {}

  /**
   * An opening balance as staged or as booked — a date and a signed amount in the account's own
   * currency (Hauptbuch's sign convention on both sides, so they compare directly).
   */
  record Balance(LocalDate date, BigDecimal amount) {}

  /**
   * The proposed winner, or {@code null} when there is nothing to reconcile (Money staged no
   * opening balance for this account). Returns {@link OpeningBalanceChoice#TAKE_MONEY} when the
   * target account has no opening balance of its own — Money's is simply brought in, no conflict.
   */
  static String propose(Balance hauptbuch, Balance money) {
    if (money == null) {
      return null;
    }
    if (hauptbuch == null) {
      return OpeningBalanceChoice.TAKE_MONEY;
    }
    int byDate = money.date().compareTo(hauptbuch.date());
    if (byDate < 0) {
      return OpeningBalanceChoice.TAKE_MONEY;
    }
    if (byDate > 0) {
      return OpeningBalanceChoice.KEEP_HAUPTBUCH;
    }
    // Same date: break the tie toward the non-zero one; otherwise Hauptbuch's own figure stands.
    boolean hauptbuchZero = hauptbuch.amount().signum() == 0;
    boolean moneyZero = money.amount().signum() == 0;
    if (hauptbuchZero && !moneyZero) {
      return OpeningBalanceChoice.TAKE_MONEY;
    }
    return OpeningBalanceChoice.KEEP_HAUPTBUCH;
  }
}
