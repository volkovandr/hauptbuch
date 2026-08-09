# Editing a transaction can leave the entry dock below the fold, with no auto-scroll to it

Status: needs-triage
Severity: minor
Area: Transaction register — layout (`register.css`) × row selection (`keyboard.js`)

On a small screen, the entry dock (the edit form) can sit below the visible viewport, requiring a
manual scroll down to reach it. Usually this goes unnoticed because the register is normally worked
from the bottom (newest transactions), where the dock right below the table is already close to
view. It's much more noticeable on the receipt→register "Edit transaction" jump: that lands on
whatever row backs the receipt's transaction, which can be an old one near the *top* of the table,
far from where the dock sits.

What happens today (`keyboard.js:215-229`, `scrollToBottom`): on page load / after a row-touching
htmx swap, a server-preselected row (the receipt jump's case) gets `scrollIntoView({block:
"center"})` — but that only centers the *row* inside the register's own internal scroll box
(`.register-scroll`, `register.css:47-54`, a fixed `max-height: 65vh` frame with its own
`overflow: auto`, separate from the page's scroll). It does nothing to ensure the dock itself — a
separate element below that box, in normal page flow — ends up in the viewport. On a small screen
where filter + table-box + dock together exceed the viewport height, the row can be perfectly
centered in the table while the dock is still off-screen below.

Ideally both the row *and* the form would be visible together, but the owner's own read is that may
not be achievable on small screens. The simpler, requested fix: make sure entering edit mode
scrolls the *page* so the dock/form is in view, rather than leaving that to a manual scroll.

## Comments

Filed 2026-08-09 from an owner report, most noticeable via the receipt→register "Edit transaction"
jump. Left at `needs-triage` — no fix approach agreed yet.
