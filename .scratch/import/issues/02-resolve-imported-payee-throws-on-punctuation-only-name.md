# resolveImportedPayee throws on a punctuation-only P field instead of booking payee-less

Status: needs-triage
Severity: minor (latent until f2)
Area: Import commit — payee resolution (`PayeeService.resolveImportedPayee`, plan d2)

## What happens

`resolveImportedPayee` is documented as the routine f2 calls for **every** staged row, and a
"wholly destroyed or absent name yields `null`". It guards only `payeeText == null ||
payeeText.isBlank()`. A non-blank `P` field with no usable name segment — `"-"`, `"."`, `","`,
`" - "` — is not blank, so `parseCreateNew(payeeText)` is reached, its split on `\s*[-,]\s*`
yields no name segment, and it throws `IllegalArgumentException("A payee needs a name")`.

In f2 (commit is one atomic transaction, import.md §10) one such row would abort the whole
import rather than book that transaction with `payee_id` null.

20 years of Money QIF plausibly contains a `P-` or `P.` line somewhere; the `?`-substitution
(§4.4) can also leave `P??? - ???` shapes that survive the blank check but parse to nothing.

## Where to fix

In `resolveImportedPayee`: treat "parsed to no usable name" the same as destroyed/absent —
return `null`. Either catch the `parseCreateNew` failure, or add a pre-check that the text has a
name segment. Keep `parseCreateNew` itself strict for the register's inline-create path.

## Related (same review)

- `resolveImportedPayee` renames only the lowest-id case-variant payee; pre-existing duplicate
  rows (`rewe` **and** `REWE` both already live) are not merged, so their history stays split.
  The FR-DM payee-merge tool (`implementation-plan.md` §3) is the real home for that — the
  import only needs to not *add* to the split, which it doesn't.

Filed 2026-09-03 from a `/code-review` pass during import slice e1 (findings were against the
already-committed d1/d2 range, not e1).
