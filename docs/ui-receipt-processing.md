# Personal Finance Manager — UI: Receipt Processing & Receipt Register

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.6
**Date:** 2026-08-03
**Owner:** volkovandr
**Companion to:** `requirements.md` (v0.4),
`tech-stack.md` (v0.1),
`data-model.md` (v0.3),
`ui-transaction-register.md` (v0.2)

> This document records the **interaction design** of receipt ingestion (§5.7) — the mobile
> capture surface, the PC receipt **register**, and the four-step PC **workflow** (pre-process →
> process → post-process → confirm) — together with the reasoning, in keeping with the house rule
> that the *why* must survive long after the *what* is code.
>
> Scope note: this covers the receipt *lifecycle*, the two processing **modes** (single vs batch),
> the layout and behaviour of each surface, the per-receipt **AI note**, the **redistribute-tax**
> helper, **duplicate** handling, and the receipt ↔ transaction link. It includes a **provisional
> `receipt` schema sketch** (§9) — this is the entity the data-model doc deferred to its §12
> ("Attachments"); it must be **ratified back into the data-model doc**, not treated as settled here.
> The **exact keyboard state machine** is deferred to implementation, as in the register doc.

**Changelog**
- **v0.5 (2026-08-02):** Stage-9f grilling round (owner-confirmed; full decisions in
  `implementation-plan-stage-9.md` §9f). **Review = editing the analysis result, nothing more**:
  Save persists the draft and the state stays `processed` — `committed` is the reviewed state;
  no marker for saved-but-unconfirmed (interrupted work). §6.3 sharpened: the header gains an
  **editable total** (null total → neutral hint, not ⚠); the payee picker **prefills from the
  parsed merchant**; currency ≠ account currency **warns at Save, blocks only at Confirm**
  (cross-currency commits → backlog). The category **ghost** is concrete: resolved suggestions
  are plain values, unresolved lines show the AI's raw term (`receipt_line.ai_target_text`,
  data-model v0.11) as a grey hint — which also marks targetless transfer lines. **⇄
  Redistribute is a per-line action** (spread this line over the others, remove it) — no
  Tax-line detection; base = all lines except real-account transfer legs, negatives participate,
  largest-remainder rounding, total preserved.
- **v0.4 (2026-07-31):** Stage-9c grilling round (owner-confirmed). **The ▲▼ stage axis is
  retired**: the workflow pane becomes the **processing screen** — one view per *state*, at its own
  URL, with every transition (forward or backward) an explicit named button, never auto-triggered
  (§2.2, §6). **The `discarded` state is retired** (§2.1): "seen, chose not to book" is covered by
  **Delete + keep files** (soft-deleted row, files retained); "Discard" now means **stage-undo**
  (e.g. *Discard edits*: `pre_processed` → `new`). Delete on PC always asks the 3-way
  keep/delete-files question, for `new` receipts too; the mobile grid keeps its instant × on `new`
  tiles. Pre-process mechanics settled: Save **always bakes** the edited image (EXIF-upright made
  physical; JPEG q≈0.9, long edge capped at 1568 px with a visible downscale note) and stores an
  **edit recipe** (crop/rotation/tilt/filter JSON) replayed onto the original on re-edit; the AI
  note is saved by the same Save and **survives** *Discard edits*. Keyboard: ↑/↓ walk the filtered
  list, Esc = back (read mode) / guarded Cancel (edit mode).
- **v0.3 (2026-07-21):** Stage-9 planning round (grilled & owner-confirmed; build sequence in
  `implementation-plan-stage-9.md`). **Schema ratified** into `data-model.md` §13 — that doc is now
  authoritative for the entities; §9 below is historical. **Duplicate detection +
  link-to-existing deferred** to the backlog (confirm always creates; Q-RX-2 moot until the stage-13
  matcher exists). **Reopen/re-enter added** (§7): a committed receipt can be reopened, its draft
  re-edited, and re-entered — the old transaction is soft-deleted and a new one booked; no drift
  check. **AI Vocabulary added**: the AI sees an operator-curated projection of the taxonomy
  (per-category alias / hide / **AI note** — freetext prompt guidance that can also instruct
  per-line tags and beneficiaries, echoes resolved against live entities or dropped — owned by
  `categories`, edited on category-edit) — resolves the ARCH-08 tension behind the §6.3 category
  ghosts. **Transfer lines within a receipt** made explicit: a recognised cash-withdrawal line
  (supermarket cashback) seeds as a transfer to the marked cash account, and a beneficiary line is
  a transfer into the person's debt account (a debt increase, no expense booked). Opens closed: Q-RX-3 → concrete
  `receipt` (no generalised attachment); T-RX-2 → detection config **on the account** (card last-4 +
  cash marker, account-edit screen); T-RX-3 → `receipt_line_tag` junction; T-RX-4 → keep
  `receipt_line` after commit (+ reopen). T-RX-1 polling lean stands. Parser client: official
  Anthropic Java SDK (Messages + Batches). Playwright smoke replaced by MockMvc acceptance (plan
  §14 decision, 2026-07-05).
- **v0.2 (2026-06-24):** **Removed AI-assisted cropping entirely** — tried in practice and rejected:
  manual cropping is trivial (≪ 1 s), whereas validating and correcting an AI crop decision takes
  *longer* than just doing it. Pre-processing is now **purely manual**. This **supersedes
  tech-stack §5.2** ("AI crop suggestions are advisory overlays"), which should be updated to match.
  Dropped Q-RX-1.
- **v0.1 (2026-06-24):** Initial interaction design, drafted from the brainstorm. Receipt
  **lifecycle** as the backbone (stored *state* distinct from UI *workflow step*; `deleted_at`
  orthogonal; `discarded`/`failed` side-states). Two **modes** (single = sync-feeling Messages API;
  bulk = async Batches API for the 50 % discount), one pipeline, uniform "grey + navigate-away"
  UI. Mandatory client-side **pre-processing** before analysis; analysis **deferred** so several
  pre-processed receipts can be batched. **Confirm-time** transaction creation (register stays
  clean). Mobile = camera-only capture + browse + delete-if-uncommitted. PC **receipt register**
  (master-detail) + stage-dependent **workflow pane** with two navigation axes. Full split toolkit
  in post-process (category + tags + beneficiary + note per item), **redistribute-tax** helper,
  **remaining 0,00 ✓** parse-sanity readout. Per-receipt **AI note**. **Duplicate** detection at
  confirm with create-new-vs-link-to-existing. Provisional `receipt` + `receipt_line` schema sketch.

---

## 1. Where this fits & inherited principles

From the requirements (§5.7), the tech-stack (§5 image handling, §4 htmx), and the data-model
(§12 deferred Attachments), applied here:

- **Suggest, never auto-apply; the original is sacred.** No automatic image transform and no
  automatic commit. The untouched scan is always retained on the Pi (ARCH-07 / FR-RCPT-06); the
  edited image is a *derived* artifact. The same separation applies to the **data**: the raw AI
  parse is retained immutable; the operator edits a *working copy* (§9).
- **Image work is 100 % client-side** (tech-stack §5.1): crop / rotate / tilt / grayscale /
  brightness / contrast happen in the browser canvas (Cropper.js leaf + a small self-written pixel
  pass); the Pi never does image math. Downscaling for the AI also happens here, before send.
- **Mandatory review before commit** (FR-RCPT-03) — here that is the whole **post-process** step.
- **The receipts UI is a full entry surface, not just a corrector.** It must let the operator
  supply *complete* transaction detail — payee, account, per-item category, tags, beneficiary,
  notes — exactly as the main register dock does, reusing the **same pickers** (payee §3.4,
  category §3.5, tags §3.6, beneficiary/sign rules §3.8). Receipts are simply a *second front door*
  onto the same uniform posting model; none of the workflow's conveniences are new model concepts.
- **The transaction register stays clean.** Because transactions are born only at **confirm**
  (§6.4), a receipt that is abandoned, deleted, or re-scanned never leaves a pending row behind.

---

## 2. The receipt lifecycle — the backbone

Everything else is a view over this. Two axes are kept **separate on purpose**, mirroring how the
data-model keeps `lifecycle` and `deleted_at` orthogonal on `transaction` (§3.5):

### 2.1 Stored **state** (drives the register filter; one column)

| State | Meaning | Reachable from |
|-------|---------|----------------|
| `new` | Raw scan captured; **not yet pre-processed**. | (capture); **Discard edits** from `pre_processed` |
| `pre_processed` | Image cleaned + optional AI note set; **queued, not yet analysed**. | `new` (Save), re-edit |
| `processing` | Submitted to the AI (single or batch); awaiting result. **Grey/locked.** | `pre_processed` |
| `processed` | Parse returned; working draft lines seeded; **under post-process review**. | `processing`; **reopen** from `committed` (§7) |
| `committed` | Confirmed; a transaction was created (or an existing one linked) and is attached. May be **reopened** for re-entry. | `processed` |
| `failed` | AI errored or returned nothing usable. Retry (→ `pre_processed`) or delete. | `processing` |

- **`deleted_at` is a separate, orthogonal column** (soft delete), exactly as on `transaction`.
- **There is no `discarded` state** (retired 2026-07-31, 9c grilling; it existed in v0.1–v0.3 as
  "seen, deliberately not booked, kept visible"). That need is covered by **Delete + keep files**:
  the row is soft-deleted (invisible; undelete UI is backlog-on-need), the image files stay on disk
  for investigation. **"Discard" now means stage-undo** — the explicit backward transitions of the
  processing screen (*Discard edits*, later *discard the AI result*), not a terminal verdict.
- **Delete vs re-scan.** "Too many errors, I'd rather delete and re-scan" — that is a **delete**
  (the scan was no good). On PC, delete always asks: delete files / keep files / cancel.

### 2.2 UI: one screen per state (the ▲▼ stage axis is retired)

```
  ① Pre-process  →  ② Process  →  ③ Post-process  →  ④ Confirm
   (crop/clean,      (analyse:        (review image +     (dup check,
    AI note)          single/batch)    edit items, full     create or link,
                                       split toolkit)        → transaction)
```

The pipeline above survives as the *conceptual* order, but the UI does **not** navigate "steps"
independently of state (the two-axis grid of v0.1–v0.3 is retired, 2026-07-31). The **processing
screen** (§6) renders **one view per state**, and every state transition — forward *or* backward —
is an **explicit, named button**; nothing fires automatically. `new` shows *Prepare for analysis*;
`pre_processed` shows the result plus *Analyse*; and so on. Artifacts of earlier stages (edited
image, AI note, later the draft lines) are simply visible on the later screens — no separate
"view the previous step" navigation is needed.

**Backward transitions are the undo ladder** ("discard the work of a stage"): *Discard edits*
(`pre_processed` → `new`, removing the edited image + recipe — the AI note survives), and later
"discard the AI result" (`processed` → `pre_processed`, 9e). Going back to re-crop or change the
AI note and **re-analyse** **overwrites** the parse and re-seeds the draft lines, so it asks for
confirmation if you have already edited items. (This is the remediation loop.)

---

## 3. Two processing modes — one pipeline, uniform UI

The interactive side-by-side workflow and batch cost-saving pull apart because the **Batches API is
asynchronous**. Rather than force everything through one, both modes share the pipeline and differ
only in *which API* runs and *how many* receipts move at once. **The UI is identical either way.**

> **Cost, not tokens.** Batching buys the **50 % async discount** on a pile you're not in a hurry
> for. The token-reduction tricks (image downscaling, tight output JSON schema, prompt caching,
> optional Haiku→Sonnet escalation) apply to **both** modes — they are not why you batch.

### 3.1 The "feels synchronous, is asynchronous" behaviour (both modes)

On **Analyse** (single) or **Process** (batch), the affected receipts flip to `processing`:

- The detail pane **greys out**; the item area and step buttons **disable**; a clock/spinner shows.
- **You can leave.** Hit **next receipt ▶** and work on another receipt's pre-processing while the
  first is in flight. The greyed receipt keeps churning in the background.
- Completion is **pushed into the UI** without a manual refresh: htmx **polls** a small status
  fragment (`hx-trigger="every 3s"` on the row/pane, or SSE if it proves nicer — *open, T-RX-x*).
  On completion the pane un-greys and the Post-process UI appears; if you're elsewhere, the
  **register row's state badge flips** to `processed` and an unobtrusive marker appears.

Backend (both): the request is handed to a **background worker** and the HTTP response returns
immediately with `processing` — the browser never blocks. Single uses the **Messages API** in the
worker (seconds, full price, for "I want this now"); bulk submits **one Batches API request**,
stores its `batch_id`, polls it, and on completion distributes results — each receipt flips to
`processed` or `failed`.

### 3.2 The mandatory-pre-process-then-batch flow

Pre-processing is **per-receipt and manual** (no batch pre-processing is possible) and **precedes**
analysis. So the natural backlog rhythm is:

> open `new` #1 → pre-process → **don't** analyse → next receipt → pre-process #2 → … → now several
> `pre_processed` receipts exist → **select them all** (shift/ctrl-click in the register) →
> right-click → **Process** → one batch submitted.

The single path is the same minus the accumulation: open one `new` or `pre_processed` receipt →
**Analyse** → it processes alone.

**Cropping is manual, full stop.** An earlier idea (once in tech-stack §5.2) was to have the parser
return a suggested crop box to seed Cropper.js. This was **tried and rejected**: manual cropping is
trivial — a drag, well under a second — whereas *validating and correcting* an AI's crop decision
takes **longer** than just doing it, and adds a round-trip and a failure mode for no benefit. So
pre-processing (§6.1) is **purely manual**, with **no AI involvement in the image step at all**. The
AI sees only the finished, human-cropped image. (Tech-stack §5.2 now records this manual-only stance.)

---

## 4. Mobile interface — deliberately thin

Consistent with the minimal-off-PC-exposure stance (cf. Telegram §5.16): the phone is a **capture
device**, not a finance console.

- **Camera-first capture.** Shoot → upload raw → lands `new`. Two plain HTML file inputs (refined
  stage 9b): **Take a photo** carries `capture="environment"` and opens the rear camera directly;
  **Choose from gallery** omits `capture` so the browser's gallery/file chooser appears (some
  phones suppress that chooser when `capture` is set — hence a separate affordance rather than
  camera-*only*). No getUserMedia. Selecting a file **uploads immediately** (auto-submit driven by
  the existing `keyboard.js` interaction leaf — no *new* leaf; the Upload button is the no-JS
  fallback). JPEG/PNG only, magic-byte validated, 15 MB cap. No parsing, no financial figures
  shown. Multi-shot in a row is fine.
- **Browse** a thumbnail grid of *your own* receipts — **all states, including `committed`** (a
  90-day capture window, newest first), each with a capture date and a small state dot, and
  tap-through to the full-scale original. No parsed amounts, payees, or categories on mobile.
  (Widened from "the uncommitted queue" at stage 9b — the phone is a viewer of what it captured;
  sorting/filtering controls and any transaction detail stay a PC/backlog concern.)
- **Delete** is narrowed to **`new`** scans only — an instant, no-confirmation removal of a bad
  shot (row soft-deleted, files removed). Every other state's deletion (the keep/delete-files
  choice, and a `committed` receipt's transaction-aware dialog) is a **PC** concern, off the phone.

> Rationale: precise cropping and full transaction detail belong on the PC (tech-stack §5.3 makes
> "capture on phone, edit on PC" the **default**, not a fallback). The phone just feeds the queue.

---

## 5. PC — the receipt register (the list)

Same **dense list** shape as the transaction register: thin rows, state as the **primary filter**;
**double-click** opens the **processing screen** (§6) at its own URL.

### 5.1 Columns

Left-to-right: **thumbnail · captured · txn date · state · merchant · total · account · 🔗 txn ·
status**.

- **thumbnail** — tiny preview (the edited image if present, else the raw scan).
- **captured** — capture date/time (the stable sort key, newest first).
- **txn date** — the linked transaction's booking date (issue tracker #09); blank until
  `committed`.
- **state** — the §2.1 badge; colour-coded, the spine of the list.
- **merchant / total / account** — parsed (denormalised onto the row for list/filter/search; blank
  until `processed`). Total renders by the register's currency rules (EUR bare, German-formatted
  `12,90`; non-base carries its symbol/ISO — register doc §2.9).
- **🔗 txn** — present once `committed`; click = **jump to the transaction register**, pre-selected
  and loaded in the dock (§7).
- **status** — small icons: AI note attached ✎, failed ⚠, batch-member ⌗.

### 5.2 Filter, order, search, select

| Aspect | Default | Notes |
|--------|---------|-------|
| State | **everything except `committed`** | i.e. "the work queue." One click to show committed/all. |
| Date range | **Last 90 days** of captures | Keeps the list bounded (tech-stack §4.2); widen as needed. |
| Order | **Captured, descending** | Newest first (owner decision, 2026-08-07). Re-sortable by any column. |
| Search | across merchant + AI note + parsed line text | Fuzzy, like the payee key (register §3.4). |
| Select | **shift-click / ctrl-click** ranges & sets | Multi-select feeds the right-click menu. |

**Right-click (context) menu** on a selection: **Process** (batch-analyse all `pre_processed` in
the selection), **Delete** (always the 3-way keep/delete-files dialog on PC, `new` included —
2026-07-31; there is no Discard entry, §2.1), **Re-analyse**. Single-receipt double-click opens
the processing screen (§6). Items in the selection that aren't in a valid state for an action are
skipped with a count ("3 of 5 were not ready to process"). **Multi-delete skips `committed`
members the same way** (2026-08-03) — the 5-way committed rung (§7) fires only on a single
receipt; voiding booked transactions is never a bulk action. There is **no Confirm menu entry**:
confirming is the deliberate end of a per-receipt review, on the processing screen only.

---

## 6. PC — the processing screen (the detail surface)

Opens on double-click, at its **own URL, in the same tab**, carrying the register's current
filter + order so navigation walks the same list the register shows (this also keeps the door
open for a future side-by-side embed). The screen renders **one view per state** (§2.2); common
chrome on every view:

- **↑ prev / ↓ next receipt** — steps through the **filtered, ordered list** (keys match the
  vertical list; also buttons). This is the "work through the pile" motion.
- **Back** (button, or **Esc** in read mode) — returns to the register.
- **Delete** — the 3-way keep/delete-files dialog (PC delete always asks, any non-committed
  state; the `committed` 5-way rung is §7/9g). After a delete: land on the **next** receipt,
  else the previous, else back to the list. The **Delete key** opens the same dialog.
- **State-transition buttons** are per-view, explicit, and **never fire automatically** (§2.2).

### 6.1 Pre-process (the `new` and `pre_processed` views)

Image work is client-side only (tech-stack §5); the Pi does no image math.

**The `new` view** shows the original image and **Prepare for analysis** (plus the AI note, §8,
read-only if one survives from discarded edits). *Prepare for analysis* enters **edit mode**:

- The Cropper.js leaf appears — **crop · rotate · tilt(straighten) · grayscale · brightness ·
  contrast**, live canvas preview — and the **AI note** field becomes editable. Navigation and
  Delete disappear; **Save / Cancel** appear (Esc = Cancel, confirm-guarded).
- **Save** bakes the edited image — **always, even with zero adjustments**: the immutable original
  keeps its raw pixels + EXIF orientation tag, so the edited copy is where "upright" is made
  physical (the copy the AI receives, data-model §13.1). The bake is **JPEG q≈0.9, long edge
  capped at 1568 px** (never upscaled; beyond that the Anthropic API downscales anyway, so larger
  is pure waste) — when downscaling actually occurred, the UI says so. One request saves the
  edited image, the **AI note**, and the **edit recipe** (crop box / rotation / tilt / filter
  values as JSON, data-model §13.1); the thumbnail regenerates from the edited image; state →
  `pre_processed`. All **manual** — no AI in the image step (§3.2); the **original is never
  mutated**.
- **Cancel** discards the in-progress edits and returns to the `new` view.

**The `pre_processed` view** shows the result read-only — the **edited image** (exactly the bytes
the AI will receive; click for full size) and the AI note — plus:

- **Edit** — re-enters edit mode: the **recipe is replayed onto the original** (Cropper restored
  to the saved crop/rotation/filters), the note pre-filled; Save overwrites the edited file in
  place and regenerates the thumbnail. Browsing ↑/↓ past `pre_processed` receipts does **not**
  boot the editor or replay anything — it just shows the stored edited image.
- **Discard edits** — the stage-undo (§2.2): back to `new`, edited image + recipe removed,
  thumbnail regenerated from the original. **The AI note survives** — it describes the receipt
  ("this is fuel"), not the pixels.
- **Analyse** — the ①→② action (disabled placeholder until 9e).

Saving does **not** analyse (§3.2) — you move on, or, when ready, trigger analysis (Analyse here,
or select-and-Process from the register).

### 6.2 Process (the `processing` and `failed` views)

Mostly the **Analyse** action + the wait. **Analyse** (single) submits this receipt via the
Messages API; or this receipt is part of a register batch. State → `processing`; the screen greys
and you may navigate away (§3.1). On return: `processed`, whose view is Post-process (§6.3).
The `failed` view offers **Retry** (→ `pre_processed`) — or Delete, as everywhere.

### 6.3 Step ③ Post-process (the heart of it)

```
 ┌───────────────────────────┬──────────────────────────────────────────────┐
 │                           │  Date  [ 2026-06-14 ]   Payee [ Rewe · … ]    │
 │     RECEIPT IMAGE         │  Account [ Visa ••1234 ▼ ]   (detected)        │
 │   (zoom / pan;            ├──────────────────────────────────────────────┤
 │    toggle edited↔original)│  Item            Amount   Category   Tags  →P │
 │                           │  Milk             1,19    Food:Dairy   …      │
 │                           │  Lemons           0,89    Sweets ✎    …      │
 │                           │  Beer             6,49    Drinks      Trip:.. │
 │                           │  …                                            │
 │                           ├──────────────────────────────────────────────┤
 │                           │  total 12,90   allocated 12,90   remaining 0,00 ✓│
 └───────────────────────────┴──────────────────────────────────────────────┘
```

- **Image left, editable item table right** (the requested side-by-side).
- **Header fields:** date, **payee** (existing picker + create-new §3.4; the text **prefills from
  the parsed merchant** — an exact case-insensitive match pre-selects, else create-new is one
  Enter away), **account**, currency, the **total — editable** (a mis-read total must be
  fixable, or the ✓ check below trains you to ignore it), a **Receipt no.** (prefilled from the
  parsed `receipt_number`, editable), and a free-text **Note** (empty by default; becomes
  `transaction.note` at Confirm — added 2026-08-03, a 9f omission; `receipt.note`, distinct from
  the AI note §8). Currency disagreeing with the chosen
  account's currency **warns at Save but never blocks** (the draft stays lenient); **Confirm**
  (§6.4) blocks until they match — cross-currency receipt commits are a backlog item.
  - **Account detection:** parsed from the payment line — `Bar`/cash → the account **marked as the
    cash account**; card → matched by **last-4**, configured **on the account** (account-edit
    screen — T-RX-2 resolved, 2026-07-21). No match / no payment line → operator **picks**
    the account (same field as register §3.3, which already accepts real *and* person-debt
    accounts). This is why account selection must always be available, per your note.
- **Item table = the split panel (§3.10), reused** — concretely, a shared per-line editor core
  both surfaces render (the register keeps its funding/view wrapper; receipts get their own,
  posting to receipt endpoints). Each line carries **category** (picker §3.5, with the AI's
  suggestion per-line — §3.9 generalised to per-item; the suggestion comes from the
  **AI Vocabulary** — the curated projection of data-model §13.3 — resolved term→category at
  seeding: a **resolved** suggestion is simply the line's value (override = one keystroke, the
  raw term kept as a tooltip), an **unresolved** one renders as a grey non-committing **ghost
  hint** ("AI said: …", from `receipt_line.ai_target_text`) — which also marks a targetless
  transfer line for what it is; a line's target may also be a **real account**, i.e. a transfer leg —
  a recognised **cash-withdrawal line** (supermarket cashback, *Bargeldauszahlung*) seeds as a
  transfer to the marked cash account, and the reused split panel already supports split
  transfers), **tags**
  (chips §3.6), **beneficiary** `→ Person` (§2.6/§3.8), and a **note** (§3.7). This delivers "full
  transaction detail, same as the main register."
- **`remaining 0,00 ✓` readout** reconciles **Σ items vs the (editable) total** — here it doubles
  as a **parse sanity check**: a non-zero remaining means the AI mis-summed, missed a line, or
  there's a tax/rounding gap to resolve before commit. No total parsed → a neutral "no total"
  hint, not a warning. (At commit the paying account's −total funding leg is
  added automatically, so the transaction's posting-level sum-to-zero holds by construction —
  data-model §8.1; the item table is the expense side of that.)
- **Tax handling (your three real cases), no model change** — "Tax" is just a category leaf:
  - *Consumer receipt, tax included in the total* → nothing special; the line amounts already
    include it (the number you care about).
  - *Tax as a separate line you keep* → enter it as an item categorised **Tax**.
  - *Redistribute* → a **per-line ⇄ action** ("spread this line over the others, then remove it"
    — no Tax-detection heuristics; two VAT lines = two clicks, and it serves Pfand/rounding lines
    too): the line's amount spreads **proportionally over all other lines except real-account
    transfer legs** (beneficiary lines absorb — their items bore tax too; negative lines
    participate with negative shares), cent-level largest-remainder rounding; **total preserved**,
    `remaining` unchanged. Refused when the absorbing lines sum to zero. Optional — both
    leaving-as-Tax and redistributing are first-class.

### 6.4 Step ④ Confirm

> **Deferred (2026-07-21):** the duplicate check and link-to-existing below are **out of the
> stage-9 build** — confirm always creates. They land with (or after) the stage-13 matcher they
> share; the 1:0..1 link and this section's design are unchanged. Q-RX-2 is moot until then.

- **Duplicate check** runs here (final values known): match on **merchant + date + total** using the
  **same matching logic as statement reconciliation** (§5.8). On a hit, ask:
  - **Create a new transaction anyway**, or
  - **Link this receipt to existing transaction #N** — offered **only if #N has no receipt yet**
    (receipt ↔ transaction is **1:0..1**, §7). If #N already has a receipt, this is a *true*
    duplicate → suggest **Delete + keep files** (§2.1).
- **Create new** (or no dup): **materialise** the draft `receipt_line`s into a `transaction` + its
  `posting`s — items as expense, transfer (real-account target, e.g. cashback → Cash), or
  beneficiary (person-debt) legs, the paying account as the −total funding leg —
  set `receipt.transaction_id`, state → `committed`. Materialisation goes through the **one split
  commit path** the register uses (2026-08-03) — no second entry-point; line notes/tags, the
  header payee (resolved or created), and the header note flow onto the transaction.
- **The Confirm gate (2026-08-03).** Save stays lenient (§6.3); Confirm is the strict rung and
  **hard-blocks with a plain message**, the receipt staying `processed`:
  - a line with **no resolved target** (uncategorised item or targetless transfer — such lines
    are already excluded from the `remaining` readout, so the gap is visible before you try);
  - a category that is **no longer a postable leaf** — subdivided *after* analysis (e.g. `Car` →
    `Car:Fuel/…`); re-validated at confirm time, the message points at the line;
  - a **missing or mismatched grand total** — Σ lines must equal a *present* total; deliberately
    **no** register-style "update total" shortcut here (a mismatch means the review isn't done);
  - a missing **date** or **account**; and the **cross-currency mismatch** (§6.3).
- **Confirm does not navigate** (2026-08-03): the pane flips to the **`committed` view** in place
  — the §6.3 editor rendered **read-only** from the same fragments (image alongside, parse
  telemetry kept) with **Edit transaction** (the §7 jump), **Reopen**, and **Delete** (the 5-way
  rung, §7) plus the common chrome. The pile rhythm is "commit, ↓, next receipt" — seeing what
  got booked for a beat beats auto-advancing away from it.
- **Link to existing:** attach the image + parsed metadata to #N (set `transaction_id`), state →
  `committed`, **no** new transaction. Whether to also push the parsed splits onto a bare existing
  transaction is **open (Q-RX-2)** — lean: offer it only if #N is a single unsplit line, else just
  attach the image.

---

## 7. Receipt ↔ transaction link

- **Cardinality 1:0..1.** A receipt has at most one transaction (its committed result, or one it
  was linked to); a transaction has **at most one** receipt. Your dup "link only if no existing
  receipt" requires exactly this.
- **Receipt is born without a transaction** and may die without one (delete) — the whole
  point of confirm-time creation (keeps the register clean).
- **Jump both ways.** From a `committed` receipt, the **Edit transaction** button → the register
  with the transaction **scrolled + selected + loaded in the dock**; the register derives its
  filter *from the transaction* (its funding account, a date range covering it), discarding the
  last-used filter, so the row is guaranteed visible (2026-08-03). From the register, the
  **receipt paperclip** (register §2.10) opens the receipt's `committed` view.
- **Reopen & re-enter (added 2026-07-21).** A `committed` receipt may be **reopened** — state back
  to `processed`, the transaction untouched until re-confirm — its draft lines re-edited, and
  **re-entered**: the old transaction is **soft-deleted** (postings with it; soft-delete *is* the
  void mechanism, no new lifecycle value), a new transaction is materialised from the edited
  draft, and the link repoints. Deliberately **no drift check**: re-enter always overwrites, even
  if the transaction was hand-edited in the register after commit (owner call). Every previously
  booked version stays inspectable as a soft-deleted record. UI (2026-08-03): **Reopen is
  instant** — no dialog, nothing is written to the ledger; on a reopened receipt the confirm
  button reads **"Re-enter"** and carries an `hx-confirm` naming the consequence ("voids
  transaction #N, including any edits made in the register, and books a new one") — the
  no-drift-check call made visible exactly where it fires.
- **Deleting a `committed` receipt** (the delete ladder's last rung, 2026-08-03): a **5-way
  dialog** on two axes — transaction: **void** (soft-delete, the standing void mechanism) or
  **keep**; files: delete or keep — plus cancel. Every non-cancel choice soft-deletes the receipt
  row **with `transaction_id` left in place**: live-link queries (the paperclip, the 1:0..1
  guard) scope to `deleted_at is null`, so unlinking is an *effect*, not a column write, and the
  audit trail ("this dead receipt once booked #N") survives. A kept transaction is thereafter
  indistinguishable from a hand-entered one — intended.

---

## 8. The per-receipt AI note

A freetext, set at **Pre-process** (§6.1, saved by the same Save as the image; it **survives**
*Discard edits*), stored on the receipt (`receipt.ai_note`) and sent with the image to steer the
parse in confusing cases. Two kinds you described:

- **Supply what the receipt omits** — a bare credit-card slip with no detail: *"this is fuel"* → the
  AI emits a single **Fuel** item for the whole total with the right category. (The note can carry
  the *whole-receipt* interpretation, not just per-item hints.)
- **Override a default categorisation** — *"treat Lemons as Sweets"* (you're baking a dessert) → the
  item the AI would file under **Fruit** comes back under **Sweets**.

**Mechanics & cost.** The note is concatenated into the prompt **after** the cached prefix (system
prompt + instructions + few-shot examples), so it **does not break prompt caching**; only the small
per-receipt suffix + image vary. In **batch**, each request carries its own note (the Batches API is
per-request), so a batch can mix guided and unguided receipts freely. The note is retained on the
receipt for audit and for re-analysis.

---

## 9. Provisional data-model sketch — **ratified 2026-07-21 into `data-model.md` §13**

> **Historical.** The sketch below was ratified into the data-model doc, which is now
> authoritative. Deltas at ratification: `parse_json` renamed **`parse_raw`** and typed `text`, not
> `jsonb` (the model may return malformed output, and the format is not settled — JSON today,
> possibly TOON); `receipt_line_tag` junction added (T-RX-3);
> `receipt_line.account_id` clarified as the *semantic* category node (per-currency leaf resolved
> at commit); the **AI Vocabulary** table and the on-account detection columns added; lines kept
> after commit + reopen/re-enter semantics (T-RX-4). Kept for the original reasoning only.

```sql
create table receipt (
  receipt_id     bigint generated always as identity primary key,
  state          text not null default 'new'
                 check (state in ('new','pre_processed','processing',
                                  'processed','committed','discarded','failed')),
  captured_at    timestamptz not null default now(),
  source         text not null check (source in ('mobile','pc','telegram')),
  original_path  text not null,           -- raw scan on the Pi; NEVER mutated (ARCH-07)
  edited_path    text,                    -- derived, post-preprocess image actually sent to AI
  ai_note        text,                    -- per-receipt prompt guidance (§8)
  batch_id       text,                    -- Anthropic Batches id while processing (NULL for single)
  parse_json     jsonb,                   -- raw AI result, retained immutable (audit)
  -- denormalised parsed header (for register list / filter / search):
  merchant_text  text,
  receipt_date   date,
  total_amount   numeric(19,4),
  currency_code  text references currency(currency_code),
  account_id     bigint references account(account_id),   -- detected/picked paying account
  -- the link, NULL until committed:
  transaction_id bigint references transaction(transaction_id),
  deleted_at     timestamptz                              -- orthogonal soft-delete
);

-- editable working copy of the parsed lines (the post-process item table);
-- seeded from parse_json, thrown away if the receipt is deleted — transaction untouched.
create table receipt_line (
  receipt_line_id bigint generated always as identity primary key,
  receipt_id      bigint not null references receipt(receipt_id),
  description     text,
  amount          numeric(19,4) not null,    -- native currency of receipt.account_id
  account_id      bigint references account(account_id),   -- the chosen category (an account!) or person leg
  person_id       bigint references person(person_id),     -- set ⇒ beneficiary line (→ Person)
  note            text,
  sort_order      int
  -- tags on a draft line: a receipt_line_tag junction mirroring posting_tag, OR carry tags in
  -- parse_json until commit — OPEN (T-RX-3).
);
```

- **Raw vs working, twice over.** `original_path`/`edited_path` (image) is mirrored by
  `parse_json` (immutable) → `receipt_line` (editable). Both keep the source of truth pristine while
  the operator edits a derived copy — the project's standing pattern.
- **At commit**, `receipt_line`s become real `posting`s under a new `transaction` (the per-currency
  leaf §6.5 is resolved from `account.currency_code` of the paying account); `receipt_line` is then
  vestigial (keep for audit, or drop — **open**).
- **`receipt` vs a general `attachment`.** Bank **statements** (§5.8) also get a scan + a parse +
  a workflow (but *matching*, not *creating*). Whether to generalise to one `attachment` table or
  keep `receipt` distinct and parallel the statement entity is **open (Q-RX-3)** — lean: keep
  `receipt` concrete for now (avoid premature abstraction), revisit when statements are designed.

---

## 10. Decisions & rejections summary

| Area | Decision | Rejected / alternative | Why |
|------|----------|------------------------|-----|
| Backbone | Stored **state** drives both the list filter and the processing-screen view | A separate UI "step" axis (v0.1–v0.3) | State and view can never disagree; `deleted_at` stays orthogonal (2026-07-31). |
| Modes | **Both** — single (Messages, sync-feeling) + batch (Batches, async, −50 %) | Batch-only | Batch can't do live side-by-side; single is for "now". |
| Why batch | **Cost** (−50 %) | "saves tokens" | Token tricks apply to both modes; the discount is the async price. |
| Async UX | Grey + lock + **navigate away**; htmx poll for completion | Block the UI on parse | Lets you pre-process the next receipt while one is in flight. |
| Pre-process | **Mandatory, manual, client-side, before** analysis | Parse-raw-then-remediate | Owner's receipts need cleaning; matches suggest-never-auto + Pi-clean. |
| Batching pre-processed | Pre-process many, **defer** analyse, then batch-select | Analyse on pre-process finish | Accumulating `pre_processed` receipts is what makes a batch. |
| Cropping | **Manual only**, client-side | **AI-assisted crop** (tech-stack §5.2) | Tried & rejected: manual crop ≪ 1 s; validating an AI crop takes longer. No AI in the image step. |
| Transaction creation | **At confirm** | At parse (pending row) | Keeps the transaction register clean; free delete/re-scan. |
| Receipt↔txn | **1:0..1** | 1:N | Dup "link only if no existing receipt" requires it. |
| Mobile | Camera capture + browse + delete-if-uncommitted; **no figures** | Full mobile workflow | Phone is a capture device; precise work is PC (tech-stack §5.3). |
| Register | Dense list, **state is primary filter**; double-click → processing screen at its **own URL** | In-page detail pane / overlay | Back/refresh stay sane; leaves room for a future side-by-side embed (2026-07-31). |
| Navigation | **One view per state**; ↑/↓ walk the filtered list; every transition an explicit button | Two-axis receipts × stages grid (v0.1–v0.3) | The grid navigated "steps" state couldn't disagree with anyway; explicit buttons, nothing automatic (2026-07-31). |
| Discarded | **State retired**; Delete always asks keep/delete files; "Discard" = stage-undo | Terminal `discarded` state (v0.1–v0.3) | Delete + keep files covers "seen, not booked"; undo-per-stage matches the owner's mental model (2026-07-31). |
| Edit persistence | **Edit recipe** (JSON) saved with the bake; replayed on re-edit | Bake-only (pixels are the record) | A note-only tweak must not cost the crop; recipe makes edits reproducible (2026-07-31). |
| Post-process | Image left, **full split toolkit** right (cat+tags+benef+note) | Category-only correction | "Full transaction detail, same as the register." |
| Parse check | **`remaining 0,00 ✓`** reuses the split invariant | Trust the AI total | Catches mis-sums/missed lines before commit. |
| Tax | Plain line by default; **redistribute** helper (pro-rata, deletes Tax line) | Model a tax field | "Tax never matters" as a concept; both styles must be possible. |
| Duplicates | Detect at confirm (merchant+date+total, §5.8 logic); create-new **or** link-to-existing | Always create | Avoids duplicate transactions on backlog/statement overlap. |
| AI note | Per-receipt freetext, **after** the cache prefix | Global prompt only | Steers confusing receipts; preserves prompt caching. |
| Draft storage | Immutable `parse_json` → editable `receipt_line` | Mutate the parse in place | Raw vs working, mirroring original↔edited image. |

---

## 11. Open / deferred questions

| # | Question | Status |
|---|----------|--------|
| Q-RX-2 | On **link-to-existing**, push the parsed splits onto the existing transaction, or just attach the image? | **Moot for now** (2026-07-21) — link-to-existing deferred with the duplicate check (§6.4); reopens with the stage-13 matcher |
| Q-RX-3 | Generalise to one `attachment` entity (receipts **and** statements), or keep `receipt` concrete and parallel the statement entity later? | **Resolved** (2026-07-21) — concrete `receipt` (data-model §13); revisit only if stage 13 proves a common shape |
| T-RX-1 | Completion push: htmx **polling** vs **SSE** | **Polling** (lean confirmed 2026-07-21); SSE only if polling grates in use |
| T-RX-2 | `last-4 → account` map: config table vs learned-on-confirm | **Resolved** (2026-07-21) — config **on the account** (card last-4 + cash marker, account-edit screen; data-model §13.4) |
| T-RX-3 | Draft-line **tags**: `receipt_line_tag` junction vs carry in `parse_json` until commit | **Resolved** (2026-07-21) — junction mirroring `posting_tag` (data-model §13.2); the raw parse (`parse_raw`) stays immutable |
| T-RX-4 | After commit, keep `receipt_line` (audit) or drop it | **Resolved** (2026-07-21) — keep, as the middle audit link; extended with reopen/re-enter (§7) |
| Q-RX-4 | Should mobile show **state badges / a "ready to work" count**, or stay purely thumbnails? | **Minimal dot** (lean confirmed 2026-07-21) |