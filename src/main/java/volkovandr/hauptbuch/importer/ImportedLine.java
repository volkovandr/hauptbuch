package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;

/**
 * One funding or category/transfer line of an imported transaction (import.md §3). Simple, one-line
 * transactions carry exactly one; a split (a4) carries several, each with its own {@code $} amount,
 * {@code E} memo and {@code S} target.
 *
 * @param amount the signed amount, native to whatever currency the mapped account turns out to have
 *     (§5.1) — QIF names none, so this is a plain decimal, never a {@code Money}
 * @param memo the split memo ({@code E}); null for a single-line transaction, which carries no
 *     {@code E} field at all
 * @param className the Money <em>class</em> — the {@code /Class} suffix on the {@code L}/{@code S}
 *     line (import.md §8), split off here so the category map never sees it as part of the path;
 *     null when there is no suffix, or when the class name was destroyed on export (§4.4). It
 *     becomes a tag at ledger-write time, alongside the category map's own tags — that mapping is a
 *     later stage, not the parser's job
 * @param target the category path or account reference this line books to
 */
public record ImportedLine(
    BigDecimal amount, String memo, String className, ImportedTarget target) {}
