# Statements: go to the previous / next statement of the same account

Status: resolved
Category: feature (small)
Area: `statements` — `StatementRepository`, `StatementController.show` / `StatementPageAssembler`, `statement.html`, `keyboard.js`

## Need
Working through a year of statements means leaving the statement page, going back to the list and
opening the next one. The owner wants "‹ Previous" / "Next ›" on the statement page that jump to the
neighbouring statement of the **same account**. Statements are not always adjacent (gaps, overlaps,
re-uploads), so the neighbour is chosen by date, not by id.

## Accepted design (owner's decisions)
- **Order by start date.** A statement's start is its `period_start`; when that is null, the
  earliest `booking_date` of its lines. A statement with neither has no neighbours.
- Only live statements (`deleted_at is null`) of the same account.
- **Previous** = the greatest start strictly before the current start. **Next** = the smallest start
  strictly after it.
- **Tie on start (previous):** prefer the statement whose `period_end` is closest to the current
  statement's start without being after it; one whose end is after it (or unknown) ranks behind
  those. Still tied: smaller `statement_id`.
- **Tie on start (next):** smaller `statement_id` (the owner's rule only covered previous; the gap
  is the same for every candidate, so there is nothing closer to pick).
- Statements with the *same* start as the current one are not neighbours of it.
- Leaving the page discards half-typed dock input, as any other link does. No extra warning.
- **Keyboard:** `[` goes to the previous statement and `]` to the next, when the operator is not
  typing — `keyboard.js`, via `data-statement-prev` / `data-statement-next` markup.
