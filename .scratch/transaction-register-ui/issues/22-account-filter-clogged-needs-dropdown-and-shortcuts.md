# The register account filter is a wall of checkboxes — replace with a compact control plus shortcuts

Status: needs-triage
Severity: medium
Area: Transaction register — filters (`register.html`, `RegisterController`/`RegisterService`/`RegisterFilter`)

## The problem

The account filter in the register is a flat `fieldset` of checkboxes: one per real account, then
one per per-person debt leaf (`register.html` lines 39–66). With several People, each holding
balances in more than one currency, every `Name (CUR)` leaf is its own checkbox, and the block
becomes a clogged wall that pushes the date/payee row and the register itself down the page.

The template already anticipates this — the People block carries a comment: *"The block grows bulky
with many people — collapsing it is a later UX pass."* This issue is that pass, widened to the whole
filter.

## What the owner wants

1. **A compact control instead of the inline checkbox wall.** Either a dropdown-with-checkboxes or
   an expandable panel that is collapsed by default and shows a summary of the current selection
   ("All accounts", "Cash", "3 accounts", …) when closed.
2. **Common single-account views should be fast.** The owner frequently wants *only one* account, or
   *only the non-people accounts* — both are tedious today (tick one / untick many).
3. **Bulk shortcuts.** At least "add People" / "remove People" (toggle every person leaf at once).
   Natural companions: "All", "None", and possibly "only this" affordance per row. Exact set is a
   design decision for triage.

## Constraints / notes for whoever picks this up

- **Server-rendered, htmx only** (§1.6) — the filter is a plain GET form (`method="get"
  action="/register"`). A dropdown-with-checkboxes that stays a normal multi-checkbox form under the
  hood is preferable to bespoke JS; if a small JS behaviour is unavoidable it must stay a leaf and
  not thread through the app.
- The filter value is `name="accountId"` repeated; a person leaf's value is its own account id, so
  People and real accounts are already homogeneous on the wire — shortcuts only need to know which
  ids are person leaves (`register.people()` vs `register.accounts()`).
- Selection must survive dock re-renders and the edit flow, which `hx-include` the active-filter
  hidden fields (`register.html` ~238, ~287) — keep the field name/shape or update those includes
  together.
- Check `docs/ui-transaction-register.md` §2.3 (filters) and §2.6 (People as filter options) for any
  ratified wording before changing behaviour; update the doc if the filter UX changes materially.

## Comments

Filed 2026-08-28 from the owner: with multiple People holding multi-currency balances the account
filter "looks very clogged"; wants a dropdown/expandable panel with checkboxes plus add/remove-People
style shortcuts.
