package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;

/**
 * One funding or category/transfer line of an imported transaction (import.md §3). Simple, one-line
 * transactions (a2) carry exactly one; splits (a4) will carry several, with {@code E} landing on
 * {@code memo}.
 *
 * @param amount the signed amount, native to whatever currency the mapped account turns out to have
 *     (§5.1) — QIF names none, so this is a plain decimal, never a {@code Money}
 * @param memo the split memo ({@code E}); null for a2's single-line transactions, which carry no
 *     {@code E} field at all
 * @param target the category path or account reference this line books to
 */
public record ImportedLine(BigDecimal amount, String memo, ImportedTarget target) {}
