# Receipts list doesn't refresh when a background batch finishes processing

Status: ready-for-agent
Category: enhancement
Severity: medium
Area: Receipts — register list (`receipts.html`) × batch analyse poller (stage 9h)

Found by the owner testing stage 9h (batch + prompt caching, `6678aef`). Sending a selection to
**Process** (batch) works, but the receipts list page never updates on its own afterwards — rows
sit at whatever state they were in when the batch was submitted (e.g. "processing") until the
owner manually refreshes the browser, at which point the now-`processed`/`failed` rows appear. The
owner reported waiting "forever" before giving up and refreshing by hand.

## What exists today

- The batch itself finishes server-side on a schedule: `ReceiptBatchAnalyser` polls the Anthropic
  Batches API on a `@Scheduled(fixedDelay = POLL_INTERVAL_MS)` loop (`POLL_INTERVAL_MS = 30_000L`,
  `ReceiptBatchAnalyser.java:62`, the method at `:162`). This runs entirely on the server; nothing
  pushes a notification to any open browser tab.
- The **single-receipt** processing screen already solves an analogous problem for the interactive
  (non-batch) path: `receipt-process.html:464-465` polls
  `hx-get="/receipts/{id}/status"` `hx-trigger="every 2s"` while a receipt is `processing`, and the
  status endpoint (`ReceiptProcessingController.status()`) sends `HX-Refresh` once the state moves
  on.
- The **list** page (`receipts.html`) has no equivalent trigger anywhere in the template — it only
  re-renders once, synchronously, in response to the `POST /receipts/process` submission itself
  (`ReceiptRegisterController.process()`, `ReceiptRegisterController.java:93`).

## Why this needs a decision, not just a copy-paste

The per-receipt 2s poll is cheap because it watches one row. Polling the *list* is a different
shape of problem and needs explicit choices before it's built:

- What triggers the poll — always-on while any row is `processing`/batch-queued, or only after the
  owner explicitly submits a batch in this browser tab?
- Poll interval — the per-receipt precedent is 2s; a list-wide poll every 2s for a 30s-cadence
  server job is arguably too chatty.
- Scope — refresh the whole list fragment, or a smarter partial update of just the affected rows
  (the list can be filtered/scrolled, and a full-fragment swap risks fighting the owner's current
  scroll position or selection).
- Whether this generalises to the interactive per-receipt case's own `HX-Refresh`-on-completion
  idea, or is a genuinely different mechanism (SSE/websocket) given multiple receipts of a batch
  can complete at different poll ticks server-side.

## Owner decisions (grilling session, 2026-08-09)

Fact-check first: there is **no separate `batch_queued` state**. A batch member is claimed
straight into `ReceiptState.PROCESSING` (`ReceiptBatchAnalyser.start` → `analysisService.claim`)
with a `batch_id` attached — the same state an interactive single-receipt analyse uses. So "any row
processing/batch-queued" collapses to one condition, `state == processing`, and this also covers
the interactive path incidentally if the list is left open while a single receipt analyses
elsewhere.

1. **Trigger** — state-driven, not session-driven. The poll is active whenever the *currently
   rendered* rows contain any `processing` receipt, full stop — not gated on "did this tab just
   submit a batch." A fresh page load, or a second tab opened while a batch is mid-flight, picks it
   up on its own; no client-side flag to arm/disarm.
2. **Interval** — 10 s. The server-side batch poller only advances every 30 s
   (`ReceiptBatchAnalyser.POLL_INTERVAL_MS`) and a whole batch's members land together in one tick,
   so nothing can actually change faster than that; 2 s (the single-receipt precedent) would be
   pure waste here, and 30 s risks feeling laggy against the owner's original "waited forever"
   complaint. 10 s splits the difference.
3. **Scope / mechanism** — a lightweight status-check, not an unconditional re-render. The list uses
   class-based row selection (`row--selected` in `keyboard.js`) for the context-menu actions,
   assigned to specific `<tr>` DOM nodes; a naive `outerHTML` swap of `#receipt-list` on every tick
   — even when nothing changed — would destroy and recreate those nodes and silently drop the
   owner's selection every 10 s while any batch is in flight. Instead: poll a small endpoint that
   re-checks only the specific ids known to be `processing`; only when one of them has actually left
   that state does anything touch `#receipt-list`.
4. **How the refresh lands** — reuse this page's own `#receipt-list`-swap idiom (the same fragment
   `Process`/`Delete` already render, via the same `hx-swap-oob` trick this template already uses to
   clear `#receipt-menu`/`#receipt-dialog`), **not** the single-receipt screen's full-page
   `HX-Refresh`. That mechanism stays specific to `receipt-process.html`.
5. **Accepted gap** — a second, already-open tab that rendered *before* the batch was submitted
   won't pick up the change until reloaded; each tab's poll is driven purely by what it itself
   rendered. Multi-tab sync is out of scope (single-user app).

## Agreed design

- **Compute `processingIds` alongside `receipts`.** Wherever the list is (re-)rendered
  (`ReceiptRegisterController.populateList`, called by `register()`, `process()`, `delete()`), also
  derive the ids of the just-fetched rows whose `state()` is `ReceiptState.PROCESSING` and put them
  on the model as `processingIds`.
- **A self-renewing poll trigger, embedded in the list fragment.** Inside `receipts :: list`, when
  `processingIds` is non-empty, render a small trigger element (parallel to `receipt-process.html`'s
  `statusPoll`, id `receipt-list-poll`) with `hx-trigger="every 10s"`, `hx-get` to a new
  `/receipts/status` endpoint carrying the watched ids plus the current `state`/`range` filters, and
  `hx-swap="outerHTML"` targeting **itself** (no explicit `hx-target`) — so an "unchanged" tick only
  ever replaces this one inert div, never `#receipt-list`.
- **`GET /receipts/status`** (new, on `ReceiptRegisterController`, package-private like its
  siblings): takes `id` (repeated, the watched ids — same `List<Long>` convention as
  `/receipts/menu`), `state`, `range`.
  - Re-checks which of the watched ids are *still* `processing` (new `ReceiptService.stillProcessing(List<Long> ids)`,
    thin filter over `ReceiptRepository.findLiveByIds` — no new SQL; `findLiveByIds` already exists
    for the context-menu path). Belongs in the unit tier, repository mocked (it's a filter, not a
    query).
  - If the still-processing count equals the watched count (**nothing changed**): return the same
    poll-trigger fragment again (`receipts :: listStatusPoll`) with the unchanged id set — the
    self-renewing case. `#receipt-list` is not touched.
  - If it's smaller (**something left `processing`** — finished, failed, or was deleted mid-flight):
    re-run `populateList` for the given `state`/`range` and return an **out-of-band** re-render of
    `#receipt-list` (a new fragment, e.g. `receipts :: listOob`, identical content to `list` but with
    `hx-swap-oob="true"` on the outer div) plus an empty body for the trigger's own (now-obsolete)
    self-target. The freshly rendered `#receipt-list` naturally embeds its own new `listStatusPoll`
    trigger if anything is still in flight, or none at all if the queue has drained. Factor the
    shared table/empty-state markup the two fragments both need into one sub-fragment so `list` and
    `listOob` don't duplicate the `<table>`.

## Agent Brief

**Category:** enhancement
**Summary:** The receipts register list never notices when a background batch (or a
still-in-flight interactive analyse) finishes — rows sit stale until the owner manually refreshes.
Add a selection-safe, list-scoped htmx poll that only touches the DOM when something has actually
changed.

**Key interfaces (durable names — locate them, don't trust line numbers):**

- `ReceiptService` — add `stillProcessing(List<Long> ids)`: empty in ⇒ empty out; otherwise delegate
  to `receiptRepository.findLiveByIds(ids)` and filter to `ReceiptState.PROCESSING`, mapping to
  `Receipt::receiptId`. No new repository method or SQL — `findLiveByIds` already exists
  (`ReceiptRepository.java:101`) for the context-menu path and does exactly the lookup needed.
- `ReceiptRegisterController.populateList` — also put `processingIds` on the model (ids of the
  fetched `receipts` whose `state()` is `ReceiptState.PROCESSING`).
- `ReceiptRegisterController` — add `GET /receipts/status`, `@RequestParam(name = "id", required =
  false) List<Long> ids` (mirrors `menu()`'s convention) plus the usual `state`/`range` params
  (`ReceiptFilters.STATE_QUEUE`/`RANGE_90D` defaults). Implements the branch in "Agreed design"
  above. Package-private, same visibility as the controller's other handlers.
- `receipts.html` — add the `listStatusPoll` fragment (self-target, `hx-trigger="every 10s"`,
  rendered into `list` whenever `processingIds` is non-empty) and the `listOob` fragment (the
  out-of-band `#receipt-list` refresh). Extract the shared inner markup (empty-state paragraph,
  hint, `<table>`) so `list` and `listOob` both render it without duplicating the table.

**Acceptance criteria:**

- [ ] Submitting **Process** on a selection, then leaving the tab alone, shows the rows flip from
      `Processing` to `Processed`/`Failed` on their own within ~10–40 s of the server actually
      finishing — no manual refresh.
- [ ] While a batch is in flight, selecting other rows (for a second `Process` or a `Delete`)
      survives repeated poll ticks — the selection is **not** dropped by a tick where nothing
      changed.
- [ ] A poll tick where nothing changed does not re-render `#receipt-list` at all (verifiable via a
      test asserting the response body / OOB markers on an "unchanged" call vs a "changed" one).
- [ ] Once no row is `processing`, the poll trigger is gone from the rendered list — it does not
      poll forever.
- [ ] A receipt that gets soft-deleted while its batch is still running also triggers the list
      refresh (it leaves `findLiveByIds`'s result), not just the processed/failed transitions.
- [ ] Opening the register on a **different filter** than the one that submitted the batch (e.g.
      `state=all` vs `state=queue`) still polls correctly if that filtered view itself contains a
      `processing` row — the trigger is state-driven off what's rendered, not off which filter
      submitted the batch.
- [ ] `ReceiptService.stillProcessing` covered in the unit tier (repository mocked); the new
      controller endpoint's two branches (unchanged vs changed) covered in `integrationTest`
      (rendered htmx fragment / OOB attributes), consistent with "no service-level integration
      tests" (CLAUDE.md §6).
- [ ] `./gradlew check` green.

**Out of scope:**

- Multi-tab sync for a tab that rendered before the batch was submitted (accepted gap above).
- Any change to the single-receipt (`receipt-process.html`) status poll or its `HX-Refresh`
  mechanism — this is a separate, list-scoped mechanism, not a generalisation of that one.
- SSE/websockets — explicitly rejected during grilling in favour of reusing the existing
  poll-and-fragment idiom already proven on this page.

## Comments

Filed 2026-08-07 from an owner note in `docs/potential-feature-ideas.md`. The owner intended to run
a proper grilling session on the design before this became an agent brief — done 2026-08-09 (see
"Owner decisions" above); moved to `ready-for-agent`.
