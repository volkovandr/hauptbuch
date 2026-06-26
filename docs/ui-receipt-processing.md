# Personal Finance Manager — UI: Receipt Processing & Receipt Register

**Working title:** Hauptbuch (a Microsoft Money replacement)
**Status:** Draft v0.2
**Date:** 2026-06-24
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
  (§6.4), a receipt that is abandoned, re-scanned, or discarded never leaves a pending row behind.

---

## 2. The receipt lifecycle — the backbone

Everything else is a view over this. Two axes are kept **separate on purpose**, mirroring how the
data-model keeps `lifecycle` and `deleted_at` orthogonal on `transaction` (§3.5):

### 2.1 Stored **state** (drives the register filter; one column)

| State | Meaning | Reachable from |
|-------|---------|----------------|
| `new` | Raw scan captured; **not yet pre-processed**. | (capture) |
| `pre_processed` | Image cleaned + optional AI note set; **queued, not yet analysed**. | `new`, re-edit |
| `processing` | Submitted to the AI (single or batch); awaiting result. **Grey/locked.** | `pre_processed` |
| `processed` | Parse returned; working draft lines seeded; **under post-process review**. | `processing` |
| `committed` | Confirmed; a transaction was created (or an existing one linked) and is attached. | `processed` |
| `discarded` | Deliberately **not** entered (junk / true duplicate). Terminal; **not** deleted. | any non-committed |
| `failed` | AI errored or returned nothing usable. Retry (→ `pre_processed`) or discard. | `processing` |

- **`deleted_at` is a separate, orthogonal column** (soft delete), exactly as on `transaction`.
  `discarded` ≠ deleted: *discarded* is "I looked and chose not to book it" (kept for the record);
  *deleted* is "remove this row." Folding them would lose the distinction the audit trail needs.
- **`discarded` vs `deleted` vs re-scan.** Your stated workflow — "too many errors, I'd rather
  delete and re-scan" — is a **delete** (the scan was no good). *Discard* is for a fine scan you
  decide not to book (a duplicate you caught, a junk photo).

### 2.2 UI **workflow step** (what the detail pane shows; the prev/next-**stage** buttons)

```
  ① Pre-process  →  ② Process  →  ③ Post-process  →  ④ Confirm
   (crop/clean,      (analyse:        (review image +     (dup check,
    AI note)          single/batch)    edit items, full     create or link,
                                       split toolkit)        → transaction)
```

The **step** you may view is **gated by state**: you cannot open Post-process before the receipt
is `processed`. Steps ① and ④ are *actions you take*; ② is mostly *a wait* (the analyse button
lives at the boundary ①→②). Navigating "next stage" past ① on a `pre_processed` receipt does **not**
auto-analyse — it just shows the Process step with its **Analyse** button (per the mandatory-then-
deferred rule, §3.2).

**Backward navigation is allowed.** From Post-process you may step back to Pre-process to re-crop
or change the AI note and **re-analyse**; this **overwrites** the parse and re-seeds the draft
lines, so it asks for confirmation if you have already edited items. (This is the remediation loop.)

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

- **Camera-only capture.** Live camera → shoot → upload raw → lands `new`. (No gallery/file
  picker; no parsing; no financial figures shown.) Multi-shot in a row is fine.
- **Browse** a thumbnail grid of *your own* receipts — at least the **uncommitted queue**, with a
  capture date and a small state dot. No parsed amounts, payees, or categories on mobile.
- **Delete** is limited to **uncommitted** receipts (`new` / `pre_processed` / `failed` /
  `discarded`). A `committed` receipt backs a transaction (ARCH-07 link); unlinking/discarding it
  is a **PC** concern, so it is not deletable from the phone.

> Rationale: precise cropping and full transaction detail belong on the PC (tech-stack §5.3 makes
> "capture on phone, edit on PC" the **default**, not a fallback). The phone just feeds the queue.

---

## 5. PC — the receipt register (the list)

Same **dense master-detail** shape as the transaction register: a thin-row list, and a detail pane
(here the **workflow pane**, §6) that opens on **double-click**. State is the **primary filter**.

### 5.1 Columns

Left-to-right: **thumbnail · captured · state · merchant · total · account · 🔗 txn · status**.

- **thumbnail** — tiny preview (the edited image if present, else the raw scan).
- **captured** — capture date/time (the stable sort key for "work the backlog oldest-first").
- **state** — the §2.1 badge; colour-coded, the spine of the list.
- **merchant / total / account** — parsed (denormalised onto the row for list/filter/search; blank
  until `processed`). Total renders by the register's currency rules (EUR bare, German-formatted
  `12,90`; non-base carries its symbol/ISO — register doc §2.9).
- **🔗 txn** — present once `committed`; click = **jump to the transaction register**, pre-selected
  and loaded in the dock (§7).
- **status** — small icons: AI note attached ✎, failed ⚠, batch-member ⌗, discarded ⊘.

### 5.2 Filter, order, search, select

| Aspect | Default | Notes |
|--------|---------|-------|
| State | **everything except `committed` & `discarded`** | i.e. "the work queue." One click to show committed/all. |
| Date range | **Last 90 days** of captures | Keeps the list bounded (tech-stack §4.2); widen as needed. |
| Order | **Captured, ascending** | Oldest first = natural backlog order. Re-sortable by any column. |
| Search | across merchant + AI note + parsed line text | Fuzzy, like the payee key (register §3.4). |
| Select | **shift-click / ctrl-click** ranges & sets | Multi-select feeds the right-click menu. |

**Right-click (context) menu** on a selection: **Process** (batch-analyse all `pre_processed` in
the selection), **Discard**, **Delete**, **Re-analyse**. Single-receipt double-click opens the
workflow pane (§6). Items in the selection that aren't in a valid state for an action are skipped
with a count ("3 of 5 were not ready to process").

---

## 6. PC — the workflow pane (the detail surface)

Opens on double-click; the list stays reachable. **Two independent navigation axes** — this is the
"two sets of buttons":

- **Receipt axis** ◀ prev / next ▶ — steps the selection through the **filtered, ordered list**,
  loading each receipt into the pane at its current step. This is the "work through the pile"
  motion; it does not change which *step* you're on if the next receipt supports it.
- **Stage axis** ▲ prev / next ▼ — moves through the **workflow steps** (§2.2) of the *current*
  receipt, gated by state.

Mental model: **receipts horizontal, stages vertical** — a 2-D grid you walk with two button pairs.

### 6.1 Step ① Pre-process

Client-side only (tech-stack §5). The pane shows the image with the Cropper.js leaf and a small
controls strip:

- **crop · rotate · tilt(straighten) · grayscale · brightness · contrast** — live canvas preview;
  a final pixel pass bakes the **edited image** (the artifact sent to the AI). All **manual** — no
  AI in the image step (§3.2). The **original is never mutated**; any bad edit is recoverable by
  re-editing from the original.
- **AI note** (§8) — a freetext field travelling with this receipt into the prompt.

Finishing pre-process sets `pre_processed`. It does **not** analyse (§3.2) — you move on, or, when
ready, trigger analysis (single Analyse here, or select-and-Process from the register).

### 6.2 Step ② Process

Mostly the **Analyse** action + the wait. **Analyse** (single) submits this receipt via the
Messages API; or this receipt is part of a register batch. State → `processing`; the pane greys and
you may navigate away (§3.1). On return: `processed`, and the pane advances to Post-process.
`failed` lands here with a **Retry** (→ Pre-process) and **Discard**.

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
- **Header fields:** date, **payee** (existing picker + create-new §3.4), **account**, currency.
  - **Account detection:** parsed from the payment line — `Bar`/cash → **Cash**; card → matched by
    **last-4 → account** map (small config table). No match / no payment line → operator **picks**
    the account (same field as register §3.3, which already accepts real *and* person-debt
    accounts). This is why account selection must always be available, per your note.
- **Item table = the split panel (§3.10), reused.** Each line carries **category** (picker §3.5,
  with the AI's suggestion shown as a per-line ghost — §3.9 generalised to per-item), **tags**
  (chips §3.6), **beneficiary** `→ Person` (§2.6/§3.8), and a **note** (§3.7). This delivers "full
  transaction detail, same as the main register."
- **`remaining 0,00 ✓` readout** reconciles **Σ items vs parsed total** — here it doubles as a
  **parse sanity check**: a non-zero remaining means the AI mis-summed, missed a line, or there's a
  tax/rounding gap to resolve before commit. (At commit the paying account's −total funding leg is
  added automatically, so the transaction's posting-level sum-to-zero holds by construction —
  data-model §8.1; the item table is the expense side of that.)
- **Tax handling (your three real cases), no model change** — "Tax" is just a category leaf:
  - *Consumer receipt, tax included in the total* → nothing special; the line amounts already
    include it (the number you care about).
  - *Tax as a separate line you keep* → enter it as an item categorised **Tax**.
  - *Redistribute* → a small **⇄ Redistribute tax** helper takes the Tax line and spreads it across
    the other items **proportionally to each item's pre-tax share**, then removes the Tax line;
    **total preserved**, `remaining` stays `0,00 ✓`. Optional — both leaving-as-Tax and
    redistributing are first-class.

### 6.4 Step ④ Confirm

- **Duplicate check** runs here (final values known): match on **merchant + date + total** using the
  **same matching logic as statement reconciliation** (§5.8). On a hit, ask:
  - **Create a new transaction anyway**, or
  - **Link this receipt to existing transaction #N** — offered **only if #N has no receipt yet**
    (receipt ↔ transaction is **1:0..1**, §7). If #N already has a receipt, this is a *true*
    duplicate → suggest **Discard**.
- **Create new** (or no dup): **materialise** the draft `receipt_line`s into a `transaction` + its
  `posting`s — items as expense/beneficiary legs, the paying account as the −total funding leg —
  set `receipt.transaction_id`, state → `committed`.
- **Link to existing:** attach the image + parsed metadata to #N (set `transaction_id`), state →
  `committed`, **no** new transaction. Whether to also push the parsed splits onto a bare existing
  transaction is **open (Q-RX-2)** — lean: offer it only if #N is a single unsplit line, else just
  attach the image.

---

## 7. Receipt ↔ transaction link

- **Cardinality 1:0..1.** A receipt has at most one transaction (its committed result, or one it
  was linked to); a transaction has **at most one** receipt. Your dup "link only if no existing
  receipt" requires exactly this.
- **Receipt is born without a transaction** and may die without one (delete / discard) — the whole
  point of confirm-time creation (keeps the register clean).
- **Jump both ways.** From a `committed` receipt → **Open transaction** (register, scrolled +
  selected + loaded in the dock). From the register, the **receipt paperclip** (register §2.10)
  opens the receipt in this workflow pane.

---

## 8. The per-receipt AI note

A freetext, set at **Pre-process** (§6.1), stored on the receipt (`receipt.ai_note`) and sent with
the image to steer the parse in confusing cases. Two kinds you described:

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

## 9. Provisional data-model sketch (ratify into the data-model doc)

This is the entity the data-model doc deferred to its **§12 (Attachments)**. Shown here so the UI is
grounded; it must be **moved into and reconciled with the data-model doc** (naming convention §3.0;
soft-delete §3.5; per-currency leaf routing §6.5). Not settled here.

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
| Backbone | Stored **state** (filter) distinct from UI **workflow step** (pane) | One merged enum | Mirrors `lifecycle`⊥`deleted_at`; the list filters on state, the pane shows a step. |
| Modes | **Both** — single (Messages, sync-feeling) + batch (Batches, async, −50 %) | Batch-only | Batch can't do live side-by-side; single is for "now". |
| Why batch | **Cost** (−50 %) | "saves tokens" | Token tricks apply to both modes; the discount is the async price. |
| Async UX | Grey + lock + **navigate away**; htmx poll for completion | Block the UI on parse | Lets you pre-process the next receipt while one is in flight. |
| Pre-process | **Mandatory, manual, client-side, before** analysis | Parse-raw-then-remediate | Owner's receipts need cleaning; matches suggest-never-auto + Pi-clean. |
| Batching pre-processed | Pre-process many, **defer** analyse, then batch-select | Analyse on pre-process finish | Accumulating `pre_processed` receipts is what makes a batch. |
| Cropping | **Manual only**, client-side | **AI-assisted crop** (tech-stack §5.2) | Tried & rejected: manual crop ≪ 1 s; validating an AI crop takes longer. No AI in the image step. |
| Transaction creation | **At confirm** | At parse (pending row) | Keeps the transaction register clean; free delete/re-scan. |
| Receipt↔txn | **1:0..1** | 1:N | Dup "link only if no existing receipt" requires it. |
| Mobile | Camera capture + browse + delete-if-uncommitted; **no figures** | Full mobile workflow | Phone is a capture device; precise work is PC (tech-stack §5.3). |
| Register | Dense master-detail; **state is primary filter** | Separate list & workflow screens | One surface; matches the transaction register pattern. |
| Navigation | Two axes — **receipt** ◀▶ and **stage** ▲▼ | One next/back | The "two button sets"; receipts × stages grid. |
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
| Q-RX-2 | On **link-to-existing**, push the parsed splits onto the existing transaction, or just attach the image? | Open (§6.4) — lean: only if it's a bare single line |
| Q-RX-3 | Generalise to one `attachment` entity (receipts **and** statements), or keep `receipt` concrete and parallel the statement entity later? | Open (§9) — lean: concrete now |
| T-RX-1 | Completion push: htmx **polling** vs **SSE** | Decide at build; polling is the simple default |
| T-RX-2 | `last-4 → account` map: config table vs learned-on-confirm | Open; small table to start |
| T-RX-3 | Draft-line **tags**: `receipt_line_tag` junction vs carry in `parse_json` until commit | Open (§9) |
| T-RX-4 | After commit, keep `receipt_line` (audit) or drop it | Open (§9) |
| Q-RX-4 | Should mobile show **state badges / a "ready to work" count**, or stay purely thumbnails? | Open (§4) — lean: minimal dot only |