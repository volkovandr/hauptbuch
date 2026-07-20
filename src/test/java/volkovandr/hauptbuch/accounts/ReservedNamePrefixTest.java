package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): the reserved entry-field sigils (data-model §7, plan stage 8b.1). An
 * account, category, or person name may not <em>begin</em> with one of the four keywords the dock's
 * pickers parse, so a typed {@code for Max} is never ambiguous with a person actually named "for
 * Max". A UI-parser convenience, upheld in the service layer only — no DB constraint.
 */
class ReservedNamePrefixTest {

  @Test
  void rejectsEveryReservedPrefix() {
    assertThat(ReservedNamePrefix.isReserved("to Cash")).isTrue();
    assertThat(ReservedNamePrefix.isReserved("from Cash")).isTrue();
    assertThat(ReservedNamePrefix.isReserved("for Max")).isTrue();
    assertThat(ReservedNamePrefix.isReserved("by Max")).isTrue();
  }

  @Test
  void isCaseInsensitiveAndIgnoresLeadingWhitespace() {
    assertThat(ReservedNamePrefix.isReserved("For Kids")).isTrue();
    assertThat(ReservedNamePrefix.isReserved("BY Max")).isTrue();
    assertThat(ReservedNamePrefix.isReserved("  to Cash")).isTrue();
  }

  @Test
  void allowsNameThatMerelyStartsWithTheLetters() {
    // The separating space is what makes a sigil — "Forest" and "Bytes" are ordinary names.
    assertThat(ReservedNamePrefix.isReserved("Forest")).isFalse();
    assertThat(ReservedNamePrefix.isReserved("Bytes")).isFalse();
    assertThat(ReservedNamePrefix.isReserved("Toys")).isFalse();
    assertThat(ReservedNamePrefix.isReserved("Fromage")).isFalse();
  }

  @Test
  void allowsReservedWordAwayFromTheStart() {
    assertThat(ReservedNamePrefix.isReserved("Gifts for Max")).isFalse();
    assertThat(ReservedNamePrefix.isReserved("Paid by card")).isFalse();
  }

  @Test
  void treatsBlankAndNullAsUnreserved() {
    // Blankness is the callers' own check, with their own message — this rule stays single-purpose.
    assertThat(ReservedNamePrefix.isReserved(null)).isFalse();
    assertThat(ReservedNamePrefix.isReserved("   ")).isFalse();
  }

  @Test
  void bareSigilWordAloneIsNotReserved() {
    // "To" as a whole name carries no target after it, so it cannot be mistaken for a sigil.
    assertThat(ReservedNamePrefix.isReserved("to")).isFalse();
    assertThat(ReservedNamePrefix.isReserved("by")).isFalse();
  }

  @Test
  void checkThrowsNamingTheOffendingPrefix() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> ReservedNamePrefix.check("for Max"))
        .withMessageContaining("for");
  }

  @Test
  void checkPassesOrdinaryName() {
    ReservedNamePrefix.check("Food");
  }
}
