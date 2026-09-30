# A monthly template started on a month's last day should stay on the last day

Status: resolved
Category: bug
Severity: medium
Area: Recurring — `Schedule.occurrence` (`Schedule.java`), data-model §14.1

Found 2026-09-30 by the owner. A monthly template whose start date is the last day of a month —
e.g. 30 Sep 2026 — books its next occurrence on 30 Oct 2026. The owner expects 31 Oct 2026: a
schedule that starts on a month's last day means "the last day of every month".

## Current behavior

`Schedule.occurrence(index)` does `startDate.plusMonths(index * every)`
(`Schedule.java`, the `MONTH` case). `plusMonths` keeps the start's day-of-month and clamps only when
that day does not exist in the target month. So a 30 Sep start yields 30 Oct, 30 Nov, 30 Dec, …
(and a 28 Feb start yields 28 Mar, 28 Apr, …). Data-model §14.1 documents exactly this: "Monthly
repeats on the start date's day-of-month, or on the month's last day where that day does not exist".

## Desired behavior

For the `MONTH` unit (any `every`, so quarterly and "every 2 months" too): **when the start date is
the last day of its month, every occurrence falls on the last day of its month.**

- 30 Sep 2026, every 1 month → 30 Sep, **31 Oct**, 30 Nov, 31 Dec, 31 Jan, 28 Feb, 31 Mar, …
- 28 Feb 2026 (non-leap), every 1 month → 28 Feb, 31 Mar, 30 Apr, … (the start *is* the last day of
  February, so it sticks to month-end; 29 Feb in leap years).
- 31 Jan, every 1 month → unchanged (31 Jan → 28 Feb → 31 Mar): already month-end.

A start that is **not** the last day of its month keeps today's rule, including the clamp and no
drift: 30 Jan → 28 Feb → **30 Mar** (existing `monthlyOnThe30thKeepsThe30thAfterFebruary`), 15th →
15th, 29 Jan → 28 Feb → 29 Mar.

The rule is decided by the **start date only** (computed from the start, never from the previous
occurrence — the existing no-drift principle stays).

## Decisions already taken (do not reopen without the owner)

- The "last day of month" meaning applies to **month-end starts of any length**, including 28/29
  Feb and 30-day months. A user who wants "the 30th always" from a 30-day month cannot express it
  with a month-end start; they start from an earlier month's 30th (30 Jan), which keeps the day.
- **Yearly is out of scope** and unchanged (a 29 Feb start still falls back to 28 Feb in non-leap
  years; a 28 Feb start stays on 28 Feb).
- Days and weeks are unaffected.

## Watch out: already-booked templates

Booking is driven by the cursor `booked_through` and the occurrences in
`(booked_through, min(today + lead_days, end_date)]` (`RecurringBookingService`, lines ~83-107), with
a unique stamp `(recurring_template_id, occurrence_date)`. For an **existing** month-end template
this change moves future dates (30th → 31st). If a month was already booked on the 30th and
`booked_through` is, say, 30 Oct, the new 31 Oct occurrence is **after** the cursor and would book
a second October row. The implementer must check this and decide how to avoid a duplicate —
e.g. treat a month that already has a stamped occurrence as handled, or leave `booked_through`
consistent — and **test it**. Pending (not yet confirmed) occurrences are replaced on template save
(ADR 0002); booked/confirmed rows are not rewritten.

If the safe handling turns out to need a product decision (e.g. should already-booked 30th rows
be moved?), stop and ask the owner rather than guessing.

## Acceptance criteria

- [ ] `Schedule` month arithmetic implements the rule above for `every` ≥ 1, through
      `occurrence`, so `nextOccurrences`, `occurrencesBetween`, `endDateAfter` and the cost
      figures (`RecurringCosts`) all agree.
- [ ] 30 Sep 2026 monthly → next occurrence is 31 Oct 2026.
- [ ] Non-month-end starts behave exactly as before (existing `ScheduleTest` cases pass unchanged).
- [ ] No duplicate booking for an existing template whose month was already booked on the old
      date (see above), covered by a test in the tier that owns the booking service.
- [ ] Data-model §14.1's Schedule bullet is updated to state the month-end rule (and the yearly
      exception), keeping the doc lean.
- [ ] `./gradlew check` green.

## Tests (per CLAUDE.md §6)

- **Unit (`ScheduleTest`):** 30 Sep start (→ 31 Oct, 30 Nov, 31 Dec); 28 Feb non-leap start
  (→ 31 Mar, 30 Apr); 29 Feb leap start (→ 31 Mar); 31 Jan start unchanged; 30 Jan start unchanged
  (→ 28 Feb → 30 Mar); every-2-months and every-3-months from a month-end start; `endDateAfter`
  with a month-end start; a window straddling a 30/31 month.
- **Unit (`RecurringBookingServiceTest`, repositories mocked):** the no-duplicate handling chosen
  for already-booked months.
- No SQL-resident logic, no new migration.

## Comments

Filed 2026-09-30 from the owner's report; scope and the Feb/30-day decisions confirmed in
discussion.
