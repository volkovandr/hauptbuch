package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import volkovandr.hauptbuch.debts.PersonTarget;

/**
 * The funding-leg sigil as a <em>checked assertion</em> (register §3.5, issue
 * transaction-register-ui/06), shared by {@link DockCommitService} and {@link DockSplitService}.
 *
 * <p>When the Account field named a person ({@code for}/{@code by}) rather than a real account, the
 * sigil does not <em>set</em> the funding leg's direction — the counterpart does that (register
 * §3.8), a leading {@code −} flips it — the sigil only <em>asserts</em> it. Once every line has
 * been summed, the derived funding leg's sign must match the claim: {@code for} means the person
 * ends up owing you more (a <em>debit</em> on their leg, {@code +}); {@code by} means you owe them
 * more (a <em>credit</em>, {@code −}). A net of exactly zero is unverifiable and commits. A genuine
 * disagreement is refused with an explanation rather than silently corrected — flipping the sign of
 * money on the user's behalf is how books go quietly wrong.
 *
 * <p>Only the derived funding leg can disagree: every right-side leg declares its own direction by
 * construction, so right-side sigils need no check. This one comparison reproduces all six rows of
 * §3.5's ratified sigil table and extends them to any number of split lines.
 */
final class FundingSigilCheck {

  private FundingSigilCheck() {}

  /**
   * Verify the funding-person sigil against the funding leg's net signed amount, or do nothing when
   * the funding leg is a real account ({@code fundingPersonDirection} blank) or the net is exactly
   * zero.
   *
   * @param fundingPersonDirection {@code FOR}/{@code BY} from the Account field, or {@code
   *     null}/blank when it named a real account
   * @param fundingLegNet the funding leg's signed amount after summing every line
   * @throws IllegalArgumentException when the sigil and the net disagree
   */
  static void verify(String fundingPersonDirection, BigDecimal fundingLegNet) {
    if (fundingPersonDirection == null || fundingPersonDirection.isBlank()) {
      return;
    }
    int actual = fundingLegNet.signum();
    if (actual == 0) {
      return;
    }
    boolean expectDebit = PersonTarget.Direction.FOR.name().equals(fundingPersonDirection);
    if (expectDebit == (actual > 0)) {
      return;
    }
    throw new IllegalArgumentException(
        expectDebit
            ? "The for sigil expects this person to end up owing you, but the amount and the "
                + "counterpart net to a credit on their side. Use by, or add a leading minus to "
                + "reverse it."
            : "The by sigil expects you to end up owing this person, but the amount and the "
                + "counterpart net to a debit on their side. Use for, or add a leading minus to "
                + "reverse it.");
  }
}
