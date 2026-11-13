# Statements: changing the payee in the Create dock should refresh the suggested category

Status: resolved
Category: enhancement
Area: statement dock (`fragments/statement-dock.html`), the dock's payee/category suggestion
Design: `docs/statements.md`; `docs/ui-transaction-register.md` (payee → category suggestion)

## Symptom
Opening **Create** on an unmatched line pre-fills the best-guess payee and the category that goes
with that payee. The payee guess is often wrong, so the category is wrong too. The operator
corrects the payee (Payee comes first in Tab order, then Category), but the category stays on the
stale suggestion and has to be retyped.

## Owner's proposal
When the payee is changed, re-derive the category from the new payee, the same way the initial
suggestion is made. This matches the Tab order: Payee → Category.

## Root cause (analysed from code)
The category suggestion is computed once, server-side, when the dock opens:
`StatementDockService.prefill` takes the longest payee name found in the bank's text and that
payee's last category (`DockPrefillService.lastCategoryOf`). The statements dock's Payee input
(`statement-dock.html:71-79`) has **no `change` wiring at all**, so correcting the payee changes
nothing else. The register's dock already has the mechanism: its Payee input does
`hx-get="/register/ghost"` on `change`, and `RegisterEntryController.ghost` returns the `ghost`
fragment (`entry-dock.html:800`) that OOB-fills the category input and the hidden `categoryId`.

That fragment cannot be reused as is: it hardcodes the register's ids (`#entry-category`,
`#entry-ghost`, `#entry-category-resolved`) while the statement dock uses `#dock-category` and
`#entry-category-resolved`.

## Accepted solution (agent brief)
- Payee input in `statement-dock.html`: `hx-get` on `change` to a ghost endpoint, `hx-include` the
  payee field only, `hx-swap="none"`-style OOB response. Prefer **one shared endpoint/fragment**
  parameterised by the category input's id over a second copy of the ghost fragment; if that makes
  the register's template awkward, add a statement-dock-specific fragment but reuse
  `GhostSuggestionRepository` and the payee lookup (`payeeService.findExisting`) — no new query.
- Which suggestion: the payee's **last** category (`lastFor`), the same as the dock's own initial
  pre-fill, so the first guess and a re-pick agree. (The register's `suggestFor` is "most common";
  do not mix the two within the statement dock.)
- Behaviour (follows the register): the new payee names an existing payee with history → replace
  the Category text **and** the hidden `categoryId` (the fragment must keep
  `data-counterpart-resolved` on the replaced span, see the comment at `entry-dock.html:818`).
  Brand-new payee, or one with no history → leave the category untouched. Overwriting is
  unconditional: the operator's order is Payee, then Category, so a hand-typed category before
  re-picking the payee is not a case worth protecting.
- A resolved category that is a transfer target or foreign-currency leaf must still trigger the
  dock's cross-currency refresh (`htmx:afterSwap from:#entry-category-resolved`, line 58) — verify
  that the OOB fill fires it.
- Register dock: already behaves this way; no change.
- Depends on issue 27 only in that both touch the Category picker; the OOB fill sets the id
  directly, so it does not suffer the stale-id race.

## Tests
- `integrationTest` (MockMvc): the ghost endpoint for the statement dock returns the last category
  for an existing payee, nothing for an unknown or history-less one, and the rendered fragment
  carries the OOB category input + hidden id + `data-counterpart-resolved`. Assert the rendered
  `hx-get` on the Payee input (htmx attributes need `th:attr` — see the htmx-attrs memory/rule).
- `browserTest` only if the cross-currency refresh interplay proves to need it.

## Comments
