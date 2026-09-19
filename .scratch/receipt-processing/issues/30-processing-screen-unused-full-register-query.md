# Opening/reviewing a processed receipt runs a full, unbounded register query it never uses

Status: ready-for-agent
Category: bug
Severity: high
Area: Receipts — post-process editor (`ReceiptProcessingController.addEditor`, `RegisterService.view`,
`RegisterRepository.findRows`)

Reported 2026-09-19 by the owner: opening a `processed` receipt on the PC processing screen hangs
for roughly a minute, `top` on the Raspberry Pi shows the Java process over 200% CPU, and PostgreSQL
shows a large burst of `SELECT` activity at the same time. It is much worse right after triggering
AI analysis from the UI: the status poll (`/receipts/{id}/status`) correctly waits for `processing`
to finish (confirmed in the logs) and fires `HX-Refresh`, but the resulting full-page reload then
hangs the whole app — every click either does nothing or errors, with **"unable to get a connection
from the pool"** in the logs, and the Pi's load average goes over 10 on 4 cores.

## Root cause

Every render of the receipt editor pane builds the shared register datalists via
`ReceiptProcessingController.addEditor` (`ReceiptProcessingController.java:574-594`):

```java
model.addAttribute(
    "register", registerService.view(new RegisterFilter(List.of(), null, null, null, null)));
```

`RegisterFilter`'s compact constructor defaults a null `picker` to `RegisterPicker.DEFAULT`, and
`accountIds` is empty, so `RegisterService.view()` (`RegisterService.java:80-103`) resolves
`viewed` to **every member of the default picker** — with `fromDate`/`toDate` both `null`, i.e. no
date bound at all — and then calls:

```java
List<RegisterRow> rows =
    baseCurrency.map(base -> renderRows(viewed, filter, base)).orElseGet(List::of);
```

`renderRows` → `RegisterRepository.findRows` (`RegisterRepository.java:57-132`) runs, for **every**
own account (asset/liability, including every per-person debt leaf), a windowed running-balance
query over that account's **entire live posting history** (`sum(...) over (partition by
p.account_id order by ... rows between unbounded preceding and current row)`), joined to
`transaction`, `account`, `payee`, and `country`, plus a per-row correlated subquery against
`receipt`. This is the same query the full transaction register page runs for a chosen date range —
here it runs with no range at all, across the whole ledger.

**The result (`register.rows`, exposed via `RegisterView`) is never read.** `receipt-process.html`'s
`editor` fragment only ever calls `register.payees()`, `register.accounts()`, `register.categories()`,
`register.transferTargets()`, `register.personTargets()`, and `register.tagOptions()` — small option
lists for the datalists. The expensive windowed row query is computed and then discarded on every
single render.

This call sits behind `addEditor`, which fires on:
- the initial `GET /receipts/{id}` for a `processed`/`committed` receipt (`screen()`)
- every line round-trip: add-line, remove-line, redistribute, the Account/Currency change handler
  (`renderEditor`)
- Save, Confirm, Reopen (`renderPane`)

So a single review session re-runs the whole-ledger windowed query many times, each holding a
connection for the query's full duration. The `HX-Refresh` after AI processing lands on exactly this
path (`screen()` → `addEditor`), so as the ledger grows this is also the single most expensive
request in the app, arriving right when the operator is watching for the page to come back — and any
further click (Save/Confirm/add-line/etc.) queues another full copy of the same query behind it.
With HikariCP's bounded pool, a handful of these overlapping is enough to starve every other request
of a connection, which matches the observed "unable to get a connection from the pool" error and the
Pi's CPU/load spike (Postgres computing several whole-history window aggregates concurrently on a
4-core board).

## Fix direction

`addEditor` doesn't need `RegisterView.rows()` at all — only the option lists. Options:

1. Add a `RegisterService` (or `RegisterView`) entry point that builds just the datalists
   (`accountOptions`, `payeeOptions`, `categoryOptions`, `transferTargets`, `personTargets`,
   `tagOptions`) without calling `resolveViewedAccounts`/`renderRows` at all, and have
   `ReceiptProcessingController.addEditor` use that instead of `registerService.view(...)`.
2. Failing that, keep `view()` but make row-fetching conditional/lazy so a caller that doesn't
   consume `rows()` doesn't pay for it.

Option 1 matches the existing shape best: `RegisterView`'s constructor already takes `rows` as a
separate argument from the six option lists, so splitting the assembly is a small, local change to
`RegisterService`, not a new abstraction.

## Comments
