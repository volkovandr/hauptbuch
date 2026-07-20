package volkovandr.hauptbuch.accounts;

import java.util.List;
import java.util.Locale;

/**
 * The four keywords the entry dock's pickers parse as sigils, and the rule that no account,
 * category, or person name may <em>begin</em> with one (data-model §7, register §3.5, plan stage
 * 8b.1). {@code to }/{@code from } name a transfer counterpart ({@code ledger}'s {@code
 * TransferTarget}); {@code for }/{@code by } name a person ({@code debts}' {@code PersonTarget}).
 * Without this rule a person genuinely named "for Max" would be indistinguishable from the sigil
 * {@code for} applied to a person named "Max".
 *
 * <p>This is a <strong>UI-parser convenience, not a data-model invariant</strong>, so it earns no
 * DB constraint (data-model §7) — it is upheld in the service layer of each module that names
 * things: {@link AccountService}, {@code categories}' {@code CategoryService}, and {@code debts}'
 * {@code PersonService}. Accepted cost: a category like "For Kids" must be written "Kids".
 *
 * <p>It lives in {@code accounts} rather than beside either parser because {@code accounts} is the
 * one module every name-owning module already depends on; hosting it in {@code ledger} or {@code
 * debts} would need an edge that closes a module cycle.
 */
public final class ReservedNamePrefix {

  /**
   * The reserved words, lowercase. A name is reserved when it starts with one <em>plus</em> a
   * space.
   */
  private static final List<String> PREFIXES = List.of("to", "from", "for", "by");

  private ReservedNamePrefix() {}

  /**
   * Whether {@code name} begins with a reserved sigil — case-insensitively, ignoring leading
   * whitespace, and only when a space separates the keyword from what follows (so "Forest" and
   * "Toys" are ordinary names, and the bare word "to" — carrying no target — is one too).
   *
   * <p>A null or blank name is <em>not</em> reserved: blankness is each caller's own check, with
   * its own message, so this rule stays single-purpose.
   */
  public static boolean isReserved(String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    String normalised = name.strip().toLowerCase(Locale.ROOT);
    return PREFIXES.stream().anyMatch(p -> normalised.startsWith(p + " "));
  }

  /**
   * Reject a name that begins with a reserved sigil, naming the offending keyword so the user can
   * see which word to drop.
   *
   * @throws IllegalArgumentException if {@link #isReserved(String)} holds
   */
  public static void check(String name) {
    if (!isReserved(name)) {
      return;
    }
    String word = name.strip().split("\\s+", 2)[0];
    throw new IllegalArgumentException(
        "A name cannot begin with '"
            + word
            + "' — the entry fields read to/from/for/by as transfer and person shortcuts. "
            + "Drop the leading word.");
  }
}
