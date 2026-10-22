# A "Confirmed" toast with an edit link, and Ctrl+Backspace to the last confirmed receipt

Status: open
Category: enhancement
Severity: moderate
Area: Receipts — confirm flow (`ReceiptProcessingController.confirm`), shell toasts, keyboard leaf, settings

## Symptom
Confirming is fast (about 3 s per receipt when nothing needs fixing), so mistakes are noticed a
moment too late. A just-confirmed receipt is then nearly impossible to find: it has left the work
queue, so the operator must change the state filter, scan several hundred rows, and hunt by capture
date — which may be long before the receipt was processed. The mistake stays in the register.

## Root cause
Confirm auto-advances with `HX-Redirect` to the next receipt (a full page load), and nothing
remembers what was just confirmed. Anything held only in the page is lost on that load.

## Accepted solution (owner's decisions)
1. **Toast per confirmed receipt**, bottom corner: `Confirmed! <date> · <payee> · <amount> (edit)`.
   Several stack when confirms come faster than they expire.
2. **"(edit)" is a plain link** (GET) to the receipt's page, which opens committed and read-only;
   the operator then clicks **Reopen** (the existing instant, non-destructive reopen). The extra
   click is accepted. A plain link keeps Ctrl+click / middle-click working, so several toasts can be
   opened in tabs before they vanish. No side effect on GET.
3. **Survives the redirect without a new JS leaf (CLAUDE.md §1.6):** the server keeps a short
   list in the HTTP session — receipt id, label (date, payee, amount), confirm time. Every page
   renders the entries still younger than the duration, via one shared fragment, so a toast also
   appears after the last receipt sends the operator to the register. Disappearing is pure CSS
   (fade-out animation; a negative `animation-delay` for the time already elapsed so a reload does
   not restart it) — like the existing error toast (`shell.css`, "Global htmx error toast").
4. **Duration is a setting:** a new column on the `settings` row ("confirmation toast duration,
   seconds"), default **3**, edited on the existing settings page; **0 turns the toasts off.**
5. **Ctrl+Backspace** (Ctrl on every platform, not Cmd) jumps to the last confirmed receipt (same
   page as the toast link). It works **from any page, as long as a toast is visible** — the
   processing screen and the receipts main page alike, so it still works after the very last
   receipt is confirmed and the operator lands on the register. When several toasts are visible it
   opens the **most recently confirmed** one. The shared toast fragment carries that newest
   entry's link (e.g. a data attribute the keyboard leaf reads), so the shortcut works wherever the
   fragment renders and does nothing when no toast is visible. Same rule as A/D/C (issue 37):
   **active only when not typing** (body or a focused button), because in text fields
   Ctrl+Backspace deletes a word. Chosen over Ctrl+Esc (Windows opens Start; a page cannot
   intercept it) and Ctrl+Shift+Enter. The hint line (issue 37) exists on the processing screen
   only; the main page gets no hint (owner's decision).
6. Add its entry to the shortcut hint line from issue 37: *Ctrl+Backspace last confirmed*.

## Notes
- Do not store ledger contents beyond what the toast shows (date, payee, amount) in the session.
- The session list is bounded (drop entries older than the duration, keep only the last one for the
  shortcut) so it cannot grow.
- A failed confirm (refused gate) records nothing.
- Flyway: one migration adding the settings column.

## Tests
- `integrationTest`: confirm records a session entry and the next page renders the toast; a refused
  confirm does not; duration 0 renders none; the settings page saves the duration; the toast link
  targets the receipt page.
- `browserTest`: Ctrl+Backspace navigates to the last confirmed receipt from the body and from a
  button, on both the processing screen and the receipts main page; with two toasts visible it
  opens the newer one; with none visible it does nothing; inside a text field it deletes a word
  as normal.
