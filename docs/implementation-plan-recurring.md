# Hauptbuch — Recurring templates sub-plan (slices a–g)

**Status:** Draft v0.1
**Date:** 2026-09-28
**Owner:** volkovandr
**Companion to:** `data-model.md` §14 (authoritative for every design decision),
`requirements.md` §5.3/§5.5 (FR-REC, FR-SUB), `ui-transaction-register.md` §2.3/§2.10,
`implementation-plan.md` (§3, the Recurring templates bullet),
`docs/adr/0002-recurring-occurrences-booked-by-cursor.md`

> This file is the **sequenced** build of recurring templates and follows the stage-7/9/import/
> reporting sub-plan pattern. It is **temporary**. Once the whole feature is shipped and
> owner-confirmed, the file is deleted and a short summary is folded into §3's Recurring templates
> bullet in `implementation-plan.md`.
>
> Nothing here re-specifies design. Every "what" lives in data-model §14; this file holds only the
> order, the slice boundaries, and the test tier each piece lands in. Each slice is one coding
> session and one reviewable change. Every slice after **a** ends in something visible in the UI.
> A slice is ✅ only after the owner confirms it (CLAUDE.md §7.6).

---

## Why this order

The order follows **the first moment each piece is useful**:

1. Model (**a**).
2. Defining templates (**b**).
3. Seeing them turn into register rows (**c**).
4. Dealing with the rows that need review (**d**).
5. Editing and ending a template once rows exist (**e**).
6. Failures (**f**).
7. The cost figures (**g**).

Review (**d**) comes before save semantics (**e**) because a `review` template produces pending rows
from **c** onwards, and those rows need the review surface straight away. Editing an existing
template works from **b**, but it only rebooks correctly from **e**. Until then it is safe because
it never touches booked rows.

The figures (**g**) come last. They are schedule math with no dependency on booking, so they could
move earlier if the owner wants the cost overview first. That would be a reorder, not a redesign.

---

## a — Model and schedule math ✅ **complete**

The model slice is the one exception to "every slice ends in a UI change". The owner reviews it in
the code.

- **Migration V31.**
  - The `recurring_template` table with the §14.2 columns, the line table, and the two tag tables
    (header tags and line tags). They mirror the `SplitEntry`/`SplitLineDraft` shape.
  - The `transaction` stamp: `recurring_template_id`, `occurrence_date`, the both-or-neither check,
    and the unique index.
  - The data-model §14.2 sketch is finalised against the real columns in the same change.
- **One stored shape.** A template always persists the split shape. A simple dock entry is a
  one-line split. Confirm first that `DockSplitService.commit` books a one-line split exactly as
  `DockCommitService.commit` books the simple entry, covering transfers and `for`/`by` persons. If
  they differ, stop and bring it to the owner before choosing.
- **`RecurringTemplateRepository`** in `recurring.repository`, with records for header, line and
  tags.
- **`Schedule`**: a pure value type that answers "occurrences in `(from, to]`" and "the next N
  occurrences after D". It handles days, weeks, months (with day-of-month clamping) and years (with
  29 Feb → 28 Feb), the end date, and "after K" → end date.

**Tests.**
- **Unit, carrying the weight:** every `Schedule` rule. That covers 31 Jan → 28/29 Feb → 31 Mar with
  no drift, 30th-of-month, 29 Feb yearly in leap and non-leap years, every-N for each unit, the end
  date as an inclusive bound, and "after K" for each unit.
- **Integration:** the migration applies, and every repository method round-trips, including tags
  and the stamp's check constraint and unique index.

**Done when** the schema is in and reviewed, `Schedule` is fully specified by its unit tests, and
`./gradlew check` is green.

---

## b — The recurring page and the template editor ✅ **complete**

- A **Recurring** nav entry and page listing the live templates. Each row shows the name, the
  cadence in words ("every 2 months on the 31st"), the amount and account, the confirmation mode,
  the lead time, and the **next three occurrence dates**. The dates come from `Schedule`, which lets
  the owner check the schedule math by eye. A template's **management link** opens in a new tab.
- **The editor is the dock in template mode** (data-model §14.1). It reuses the dock's entry and
  split panel, including payee, category and tag pickers, `for`/`by` persons, and cross-currency
  header fields. It adds a schedule block: name, start, cadence unit + N, end (none / date / after
  K), lead time, confirmation, and management link. There is no second entry form. Whatever the
  dock refuses, the template editor refuses.
- **Create, edit, delete** (soft delete). No booking happens yet. Deleting doesn't ask about pending
  rows because none exist until **c**; the question arrives in **e**.

**Tests.**
- **Integration (MockMvc):** create → list shows it with the right next dates; edit round-trips
  every field, including a multi-line split with line tags and a person-funded entry; delete hides
  it; the dock's validation messages appear in template mode.
- **Unit:** the cadence-in-words rendering.
- **Browser tier:** only if template mode changes what `keyboard.js` does. Reusing it unchanged
  needs no new browser test.

**Done when** the owner can define their real subscriptions, standing orders and pocket money, and
the next-dates column matches their expectation.

---

## c — The booking run ✅ **complete**

- **The run** over one template, in one DB transaction:
  1. Lock the template row.
  2. Book every occurrence in `(booked_through, min(T + lead, end)]` through the dock's `operations`
     commit path, as `confirmed` for `auto` or `pending_review` for `review`, stamped with template
     and occurrence date and dated on the occurrence date.
  3. Advance `booked_through`.

  "Today" comes from an injected `Clock`, so tests control it.
- **Triggers:** `ApplicationReadyEvent`, a midnight `@Scheduled` cron, and a save of a template (that
  template only). The row lock serialises a save-run against the midnight run.
- **New-template question:** if the start date is in the past, the save asks "book the N past
  occurrences / start from the next one" and sets `booked_through` from the answer. A future start
  already inside its lead time books without asking.
- **Register:** the **recurring marker** shares the paperclip's slot (register §2.10) and links to
  the template.
- **Module direction:** `recurring → operations` (to book) and `recurring → ledger` (the stamp,
  through a ledger API, never its tables). `ApplicationModules.verify()` must stay green.

**Tests.**
- **Unit (repositories mocked, fixed `Clock`):**
  - the window arithmetic;
  - catch-up after "downtime" (the clock jumps 40 days and books every missed occurrence once);
  - a second run on the same day is a no-op;
  - `auto` versus `review` lifecycle;
  - capping at the end date;
  - the new-template choice.
- **Integration:** a booked transaction carries the stamp, and the `ledger` repository's stamp
  methods round-trip; MockMvc: saving a past-start template with *book* puts the rows in the
  register with the marker, and *start from next* puts in none.
- **`sqlLogicTest`:** none unless a query here grows grouping, windows or joins beyond two tables.

**Done when** a template produces register rows on save and at the next midnight or restart, a
missed month catches up, nothing ever double-books, and `./gradlew check` is green.

---

## d — Reviewing pending occurrences ✅ **complete**

- **Register Pending only filter** (register §2.3). This covers recurring `review` rows and receipt
  zero-amount placeholders alike.
- **The dock on a pending row:** Save confirms (even a future-dated one), Cancel leaves it pending.
  Check that saving with **no changes** still confirms. The existing edit path may treat an
  unchanged save as a no-op, and a one-click confirm depends on it not doing so.
- **Main page:** a "N pending to review" line with overdue rows (dated before today) called out,
  linking to the register with Pending only on and all accounts ticked. The line is hidden at zero.

**Tests.** Integration (MockMvc): the filter returns only pending rows, and the main-page link
reproduces them; an unchanged Save flips `pending_review → confirmed`; Cancel leaves the row
pending; the line counts and calls out overdue rows correctly. If the filter becomes a
`filter-groups.js` group, the browser tier covers its toggle.

**Done when** the owner can go from the main page to every pending occurrence and confirm or fix
each one with the dock.

---

## e — Editing and ending a template once rows exist

- **Save on an existing template** (data-model §14.3):
  1. Hard-delete its live `pending_review` rows dated ≥ today.
  2. Pull `booked_through` back to `min(booked_through, today − 1)`.
  3. Run, skipping occurrence dates that already hold a confirmed or voided row from this template.

  The hard delete is a **new `ledger` operation**, scoped to pending, stamped rows only, and logged
  at INFO with the transaction id. It removes postings and posting tags with the transaction. No
  other caller may use it.
- **End or delete dialog:** if a save moves the end date earlier and cuts off existing pending rows,
  or on delete, ask *keep all pending* / *keep only those dated before today* / *remove all
  pending*, with the note that confirmed transactions always stay.

**Tests.**
- **Unit (fixed `Clock`):**
  - an amount edit replaces future pending rows;
  - 15th → 16th → 15th leaves exactly one live pending row per month;
  - a voided occurrence stays skipped across an edit;
  - a confirmed future row blocks its own date only;
  - moving the start date into the past books nothing past;
  - raising the lead time from 3 to 14 fills the gap at once;
  - past rows are never touched.
- **Integration:** the hard-delete operation removes the transaction, postings and tags and refuses
  a confirmed or unstamped row; MockMvc: each of the three dialog choices.

**Done when** editing a live template behaves exactly as ADR 0002 describes and the register shows
it immediately.

---

## f — Booking failures and referential integrity

- **Failure handling:** a template whose occurrence can't book rolls back alone. Its cursor stays,
  it logs WARN, and it retries on every run. The failure is recorded on the template (reason + since
  when) so the page can show it.
- **Main page warning** per failing template ("could not book: …"), linking to the template. The
  recurring page shows the same state on the template's row. The warning clears on the first
  successful run.
- **Merges and deletions:**
  - A person merge rewrites template references to the merged-away person.
  - Deleting a category or account that a live template uses is refused, naming the template.
  - Closing an account is allowed and surfaces as a booking failure, because closing is reversible.
- **Breaking the module cycle.** `operations` defines a small public interface, "rewrite references
  from A to B", in its root package. `recurring` implements it, and the merge services call every
  implementation. `operations` never depends on `recurring`. This is the proposed resolution of the
  cycle noted in §3; confirm it with the owner before building.

**Tests.** Unit: a failing template doesn't block the next one and its cursor stays put; the
failure record is set and cleared. Integration: the person merge rewrites template lines; category
and account deletion are refused with the template's name; MockMvc shows the main-page warning and
clears it after the cause is fixed. `ApplicationModules.verify()` stays green.

**Done when** no occurrence can be skipped silently and no merge or deletion leaves a template
pointing at nothing.

---

## g — The figures and the end reminder

- **Per-template figures** (data-model §14.4): per month and per year; with an end date, also
  already, yet to pay, and total. They are shown in native currency, with base at today's rate
  where the currencies differ, and carry a `.help` marker stating they are schedule math, not
  bookkeeping (CLAUDE.md §5).
- **The Recurring cost summary** on the recurring page: expense (per top-level category), income,
  transfer, and net, per month and per year in base.
- **End reminder:** the checkbox + days fields in the editor, and a main-page warning from
  `end_date − days` onwards that links to the template. **Dismiss** unticks the checkbox.

**Tests.**
- **Unit (carrying the weight):**
  - the normalisation (days/weeks through 365, months/years exact);
  - "already" counting occurrences through today whether booked or not;
  - leg classification (a loan split into transfer + expense, a person-funded expense, an income
    template);
  - the net total;
  - the reminder's start date.
- **Integration (MockMvc):** the summary renders with a mixed-currency set; dismissing the reminder
  unticks it and removes the warning.

**Done when** the recurring page answers "what do my recurring payments cost me per month and per
year", and an ending template warns on the main page as configured.

---

## Cross-cutting, not a slice

- **No fourth JS leaf** (CLAUDE.md §1.6). Template mode reuses the dock and its leaves as they are.
- **Logging** (CLAUDE.md §5): creating or deleting a template and the hard delete are INFO; each
  booked occurrence is DEBUG, as any transaction record is; a booking failure is WARN.
- **Deferred, out of every slice:**
  - forecasting from templates (FR-FC-01);
  - MCP tools;
  - matching occurrences against statement lines (FR-STMT-03);
  - price-change reminders.
