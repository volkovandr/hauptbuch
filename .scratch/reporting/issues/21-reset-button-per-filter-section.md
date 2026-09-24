# Each filter section needs a Reset button to clear its own filter in one click

Status: ready-for-agent
Category: enhancement
Severity: low
Area: Reporting (`analytics` module, `fragments/report-filters.html`, `ReportFilterView`)

## What happens

Once some nodes are ticked in a filter section (e.g. a handful of accounts in **Account**), the only
way back to "all accounts" (no filter) is to untick each one by hand. The long lists sit in a small
scroll box (see `03-filters-panel-sections-stacked-with-cramped-scroll-boxes.md`), so finding every
ticked node means scrolling up and down inside it. That is tedious and easy to get wrong.

## Wanted (owner, 2026-09-24)

A **Reset** control in **every** filter section (Category, Account, Tag, Payee, Person, Currency,
Account type, reconciliation, note text). One click removes that section's own filter and re-renders
the Report, exactly as if every box were unticked and Apply pressed. The other sections' filters, and
the rest of the draft, are kept.

- Show it only when the section has an active filter; an empty section has nothing to reset.
- Reset changes the draft like any other edit, so it works the same on a Preset, `/reports/new` and a
  saved Report.

## Notes for the implementer

- Each section's `<form>` already carries `section.otherParams()`: the whole draft minus this field's
  own filter (`ReportFilterView.withoutFilter`). Rendering the page from exactly those params *is* the
  reset. So Reset can be a plain link or `hx-get` to the page path with those params, swapping the frame
  like Apply does, with no new endpoint and no JS. `filter-groups.js` is not involved.
- Keep the draft's expansion (`expanded`, issue 02) in the params it carries, as every other settings
  form does.
- Label it **Reset** (or "Clear"); it is a text button next to Apply, not an icon, so no `title`
  tooltip is needed (CLAUDE.md §5).
- **Tests.** Integration: a draft with an Account filter and a Payee filter renders a Reset link on
  both sections, and the Account section's link carries the Payee filter but not the Account one. No
  Reset link on an empty section.

## Comments

Filed 2026-09-24 from owner testing, right after issues 06/11 were confirmed. The owner asked for it
**before reporting slice f**. Related: `03-filters-panel-sections-stacked-with-cramped-scroll-boxes.md`
(the cramped lists that make unticking painful).

Implemented 2026-09-24 (branch `feat/reporting`), awaiting owner confirmation. Each section record
gains `filtered`. A filtered section renders a hidden `filter-reset-<FIELD>` form carrying its
`otherParams()` alone (expansion included), plus a ghost **Reset** button beside Apply that submits
that form via `form=`. It re-GETs the page exactly as Apply does, with no JS and no new endpoint.
A saved Report whose own spec holds the filter drops it too: the reset params always form a full
draft.
