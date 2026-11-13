# Statements: opening the dock rebuilds the whole statement page

Status: resolved
Category: performance
Area: `StatementController.show`, `StatementPageAssembler`, `statement.html`,
`fragments/statement-dock.html`, `keyboard.js` (`focusStatementDock`)
Design: `docs/statements.md` §6.4; no domain rule involved

## Symptom
Clicking Create / Amend (on a line) or Edit (on an extra) takes as long as any other click on the
page (~2 s on the Pi), though nothing changes on the page except that a form appears.

## Root cause
Create / Amend / Edit are `hx-get` links to `/statements/{id}?dock=…` (or `?extra=…`), the full page
route. It runs `StatementPageAssembler.addPage` — match review, balance checks, PDF lookups,
account lists, the whole table render — and swaps `<main>`; only the dock row is new. Cancel does
the same to throw the dock away again.

## Accepted solution (option A; owner, 2026-11-13)
A dock-only endpoint that returns just the dock row, appended under the clicked row:

- `GET /statements/{id}/lines/{lineId}/dock` (Create; `?posting=` for Amend) and
  `GET /statements/{id}/extras/{posting}/dock` (Edit on an extra). Each runs only the dock prefill
  and the picker lists. A refusal (the line was matched meanwhile, only a simple two-leg
  transaction can be edited) comes back as the same error row the full page shows.
- The links keep their `href` to the full page route, so a refresh or a deep link still opens the
  dock; the htmx request goes to the dock endpoint and pushes the full-page URL.
- Only one dock at a time: opening a dock removes the one already open.
- Cancel removes the dock row without a request for the page, and pushes the plain statement URL.
- `keyboard.js` focuses the dock's Date after such a swap (the clicked link stays in the DOM, so
  the "nothing is focused" test no longer holds).
- Save is unchanged: it still re-renders the whole page (it can change other rows — a candidate
  can become an extra or move to another line — so a partial update is a separate, larger issue).

## Tests
- `integrationTest` (`StatementDockIntegrationTest`): the dock endpoints return only the dock row
  (no table, no header), prefilled; a matched-meanwhile line returns the refusal row.
- `browserTest` (`StatementDockBrowserTest`): the existing dock tests keep passing (date focused,
  page does not scroll); new: opening a second dock closes the first; Cancel removes the dock.
- Timing is not tested; the owner confirms on the Pi.

## Comments
- 2026-11-13: owner verified on the Pi.
