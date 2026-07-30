# Hauptbuch — Stage 9 Plan: Receipts (merged; subsumes former stages 10–12)

**Status:** Draft v1.0
**Date:** 2026-07-21
**Owner:** volkovandr
**Parent:** `implementation-plan.md` (stage 9 — this doc is the detail the sub-plan pattern pushes out;
deleted on completion with a summary folded back, like the stage-7 sub-plan).
**Authoritative interaction design:** `ui-receipt-processing.md` (v0.3). This doc sequences the
build; the receipt doc owns every lifecycle, workflow, and display rule — section references below
(§2.x–§8) point there unless prefixed otherwise.

> The former stages 9–12 (backend / upload UI / AI / lifecycle UI) were horizontal layers — none
> independently usable, each building machinery with no caller until a later layer. They are
> **merged into one stage 9**, sliced **vertically** instead: eight ordered sub-stages, each an
> end-to-end path that is green, demoable, and owner-confirmed on its own (the 7a–7f / 8a–8f
> shape). Migrations are slice-local, landing with the sub-stage that consumes them (the stage-7
> precedent).

**Shaping decisions (owner-confirmed, 2026-07-21):**

- **Scope.** IN: the core loop (capture → pre-process → analyse → post-process → confirm), the
  per-receipt **AI note** (§8), the **redistribute-tax** helper (§6.3), and **batch mode**
  (Batches API, −50 % — §3). DEFERRED to the backlog (plan §14): **duplicate detection +
  link-to-existing** (§6.4) — confirm always creates; the matcher arrives with statement
  reconciliation (stage 13), which needs it anyway. Q-RX-2 is moot until then.
- **Q-RX-3 closed: concrete `receipt` table.** No generalised `attachment` entity; statements get
  their own entity at stage 13, sharing only if a real common shape emerges.
- **The AI Vocabulary (ARCH-08 resolution).** The AI suggests categories from an
  **operator-curated projection** of the taxonomy — per-category *alias* (what the AI sees instead
  of the real name), *hide* flag (excluded entirely), and a freetext **AI note** (per-category
  prompt guidance — the per-category sibling of `receipt.ai_note` — steering categorisation and,
  when the note instructs it, per-line tags and beneficiaries; the AI only echoes names the note
  supplied, and echoes resolve case-insensitively against live entities or are silently dropped —
  suggestions, never creations). Owned by the **`categories` module**
  (rename/merge/subdivide must keep terms consistent, and that module already owns
  keep-the-taxonomy-consistent logic); public API `aiVocabulary()` + `resolveTerm()`; edited as an
  "AI parsing" section on the existing category-edit screen. ARCH-08 reworded accordingly
  (requirements v0.5): never transactions/balances/ledger contents; the AI Vocabulary is part of
  the parsing instructions.
- **T-RX-3 closed: `receipt_line_tag` junction** mirroring `posting_tag`; header-level tags in
  post-process stay input convenience expanding to per-line rows (the V6 pattern).
- **T-RX-4 closed — and extended: keep `receipt_line` after commit, plus reopen/re-enter.**
  The draft is the middle link of the audit chain (`parse_raw` = what the AI said →
  `receipt_line` = what you edited → postings = what got booked). **Reopen** returns a `committed`
  receipt to `processed` (transaction untouched); **re-enter** soft-deletes the old transaction,
  materialises a new one from the edited draft, and repoints the link. **No drift check** (owner
  call): re-enter always overwrites, no comparison with register-side edits. No new lifecycle
  value — soft-delete *is* the void mechanism.
- **Parser client: the official Anthropic Java SDK** behind our own `ReceiptParser` interface
  (ARCH-03). It covers Messages *and* Batches natively — one client stack for both in-scope modes;
  Spring AI is not used here (its portable abstraction has no Batches support; it may still arrive
  later for the MCP *server* only).
- **T-RX-2 closed: detection config on the account** — a card-last-4 field and a cash-account
  marker, edited on the existing account-edit screen (the same
  parsing-config-on-the-entity pattern as the AI Vocabulary). Payment line `Bar`/cash → the marked
  cash account; card last-4 → matching account; no match → operator picks.
- **Adopted leans:** T-RX-1 completion push = **htmx polling** (SSE only if polling grates);
  Q-RX-4 mobile stays thumbnails + minimal state dot; `source` keeps
  `('mobile','pc','telegram')`.
- **No Playwright.** The old stage-12 text predates the 2026-07-05 owner decision dropping browser
  smoke (plan §14); the money-critical receipt flow is covered by **MockMvc controller/htmx
  acceptance** in the integration tier, like everything else.

**Module-boundary note (decided up front).** Feature screens live in their feature module:
receipt register, workflow pane, capture endpoints, worker, storage, and `ReceiptParser` all live
in **`receipts`**. The AI Vocabulary (table, API, category-edit UI section) lives in
**`categories`**; the detection fields and their account-edit UI section live in **`accounts`**.
Confirm materialises a transaction via the **`operations`** commit path (the 7b/8e precedent —
payee/category-leaf resolution and `recordTransaction` orchestration already live there); seeding
resolves note-instructed beneficiary echoes via `debts`' public person lookup. Expected new edges
`receipts → operations`, `receipts → categories`, `receipts → accounts`, `receipts → debts` are
acyclic today. Final adjudication as always by `verify()`.

---

## 9a — Docs & schema ratification (this slice; no code) ✅ **complete**

Ratify the receipt model into `data-model.md` (§13): `receipt`, `receipt_line`,
`receipt_line_tag`, the AI Vocabulary table (`category_ai_config`), the
account detection columns, and the reopen/re-enter semantics. Record the scope changes in
`ui-receipt-processing.md` (v0.3) and reword ARCH-08 (requirements v0.5). Collapse the main
plan's stages 9–12 into the merged stage entry pointing here.

**Done when:** the four docs agree with this plan and the owner confirms.

## 9b — Walking skeleton: capture → register → delete ✅ **complete** (owner-confirmed 2026-07-30)

**Goal:** a photo taken on the phone lands as a `new` receipt visible in the PC receipt register,
and can be deleted or discarded — the full storage and lifecycle plumbing, no image editing yet.

**Decisions grilled & settled 2026-07-30:**

- **Migration:** the full ratified `receipt` table (V9, data-model §13.1), complete state enum —
  later slices consume it progressively.
- **Formats:** JPEG + PNG only, validated by magic bytes (not client content type), 15 MB
  multipart cap. **PDF ingestion → backlog** (real need: Android document scans produce PDFs) —
  rejected with a clear message until then.
- **`ReceiptStorage`** (ARCH-07): storage **root from profile-specific config** (prod = Pi data
  dir, dev = throwaway local dir, tests = temp dir); DB stores **root-relative** paths.
  Layout: `originals/<yyyy>/<MM>/<yyyyMMdd-HHmmssSSS>.<ext>` (timestamp name; on collision append
  `-2`, `-3`, …); `edited/` (9c) and `thumbs/` mirror the same stem. Thumbnails: ~320 px JPEG,
  generated eagerly at upload **and self-healing at serve time** (missing file → regenerate from
  edited-else-original and store) — deleting the `thumbs/` tree is the sanctioned way to force
  regeneration after a style/size change.
- **Capture = plain HTML file input** (`accept` JPEG/PNG, `capture="environment"`): native camera
  opens directly — **camera-first, not strictly camera-only** (some Android browsers still offer
  the gallery; accepted). No getUserMedia, no new JS leaf.
- **Mobile surface `/receipts/capture`** — one standalone page (not the desktop shell): capture
  button · thumbnail grid of **all states incl. `committed`** (captured **descending**, state dot,
  no financial figures, hard-bounded to last 90 days — plain constant, maybe a setting later) ·
  tap-through to the full-scale original (native browser zoom) · delete on `new` tiles only.
  Sorting/filtering controls, transaction details on mobile → backlog. §4 is updated accordingly
  ("at least the uncommitted queue" already permits the extension).
- **Root redirect:** `/` with a `Mobi` User-Agent → 302 to `/receipts/capture`; `/?desktop`
  escape hatch skips it. Desktop falls through to the landing page.
- **The delete ladder** (by state at delete time; row is always soft-deleted — `deleted_at`;
  no undelete UI, backlog if ever):

  | State | Where | Interaction | Files |
  |---|---|---|---|
  | `new` | mobile + PC | instant, no confirmation | **removed** (original + thumb) |
  | other non-committed | PC only | modal: keep files / delete files / cancel | per choice |
  | `committed` | PC only | 5-way modal (void ± files / unlink ± files / cancel) — **9g** | per choice |

  `processing` is deletable immediately — the user never waits out an AI call; the worker
  tolerates soft-deleted rows (contract lands in 9e). File deletion is recorded against ARCH-07:
  immutable means *never edited in place*; wholesale removal of a dead scan is allowed per the
  ladder. The keep/delete-files modal is a server-rendered fragment (three-way — `hx-confirm`
  can't express it); **Discard** (any non-committed) uses plain `hx-confirm`.
- **PC register at `/receipts`** + shell nav entry: full §5.1 column set rendered with the parsed
  columns simply blank (stable layout, no 9e template churn); state filter (default work queue =
  everything except `committed` & `discarded`); **date-range filter incl. an "everything"
  option**; captured-ascending. **No search box** (everything it searches is blank until 9c/9e —
  it arrives with the first data it can find); column re-sorting deferred likewise.
- **Selection + context menu, full machinery now:** click / shift-click range / ctrl-click toggle
  + right-click menu, living in `keyboard.js` (the sanctioned interaction leaf); menu entries are
  a **server-rendered fragment** listing actions valid for the selection (9e/9h just add rows).
  No rubber-band, no select-all. Invalid-state members of a selection are skipped with a count
  (§5.2). Multi-delete/discard confirmations show the count.
- **Added from on-device testing (post-implementation, within 9b's spirit):** an **"Upload a
  scan"** control on the PC register (source `pc`, auto-submitting like the mobile capture inputs);
  the register **thumbnail links to the full-size original** (new tab; the workflow pane is 9c);
  the context menu labels its counts as **"N receipt(s)"** with a one-line Delete-vs-Discard
  explanation; and the mobile capture surface uploads **on selection** (no Upload button) via a
  small `[data-autosubmit]` hook in the `keyboard.js` leaf, with both a camera (`capture`) and a
  gallery (no `capture`) affordance. **Thumbnail EXIF orientation is honoured** (`ExifOrientation`
  reads the tag `ImageIO` drops; `ImageRotation` bakes it in) so camera portraits aren't rotated.
  The `/favicon.ico` probe is answered as a quiet 404 rather than routed through the htmx error
  boundary.
- **No auth** (app-wide LAN-open stance, unchanged by receipts; recorded once in the backlog).
- **Doc corrections land with this slice:** §4 mobile scope, the ARCH-07 file-deletion nuance,
  backlog entries (PDF ingestion, disk-reclaim purge / undelete, mobile filtering, auth stance).
- **Tests:** integration round-trips for the repository; MockMvc acceptance for upload (incl.
  magic-byte rejection), register rendering, filters, the delete ladder + discard, mobile grid,
  root redirect; storage unit tests (path scheme, collision suffix, thumbnail self-heal) against
  a temp dir.

**Done when:** a phone at the Pi's root URL lands on capture, shoots multi-shot, sees the grid,
taps to full-scale, instant-deletes a bad `new` shot (files gone); the PC register lists and
filters receipts, discard works, delete shows the keep/remove-files dialog; originals +
thumbnails sit on disk under the timestamp scheme; thumbnails self-heal.

## 9c — Pre-process: crop leaf + AI note

**Goal:** a `new` receipt can be cleaned and annotated, becoming `pre_processed`.

- **Cropper.js leaf + pixel pass** (tech-stack §5, the second sanctioned JS leaf): crop · rotate ·
  tilt · grayscale · brightness · contrast, live canvas preview, all client-side; the baked
  **edited image** uploads to `edited_path`. Original never mutated; re-edit restarts from it.
  **EXIF orientation must be baked into the edited image** (verify Cropper's canvas export is fed
  the oriented pixels — a known canvas gotcha): the immutable original keeps its landscape-pixels +
  orientation-tag form (data-model §13.1, ARCH-07), so the edited copy is where "upright" is made
  physical — the copy 9e sends to the AI. The 9b thumbnail path already proved the raw pixels are
  sideways without this.
- **AI note** (§8): freetext field stored on the receipt.
- **Workflow pane skeleton (§6):** double-click opens the pane; the two navigation axes (receipt
  ◀▶ over the filtered list, stage ▲▼ gated by state) exist with steps ① and the ② placeholder.
- **Tests:** MockMvc for the pane, the edited-image upload, and the state flip; the JS leaf itself
  stays untested per the standing rule (no browser tier).

**Done when:** a receipt can be cropped/cleaned/annotated on the PC and lands `pre_processed`,
re-editable from the original.

## 9d — The AI Vocabulary (`categories` module)

**Goal:** the curated projection exists and is editable — testable end-to-end without any AI call.

- **Migration:** `category_ai_config` (visible flag + alias + per-category `ai_note`, at most one
  row per category node) — V10.
- **Public API:** `aiVocabulary()` — the AI-facing tree (aliases applied, hidden pruned, notes
  attached); `resolveTerm(text)` — AI answer → category account (unknown → empty). Tag resolution
  by name is the same module's API (tags live in `categories`).
- **Consistency:** category rename keeps config (it attaches by `account_id`); merge/subdivide
  (existing `categories`/`operations` logic) reassigns or re-parents config rows with the node.
- **Editor:** "AI parsing" section on category-edit — visible toggle, alias field, AI-note
  textarea. Defaults (no rows) = visible under the real name, no note; zero config to start.
- **Tests:** `sqlLogicTest` for the vocabulary projection query (aliases, hidden subtrees, notes,
  resolution incl. case handling); integration round-trips + MockMvc for the editor.

**Done when:** the vocabulary renders and resolves per the crafted scenarios and is editable from
category-edit.

## 9e — Analyse (single): worker, parser, seeding

**Goal:** one `pre_processed` receipt goes through the Messages API and comes back `processed`
with seeded draft lines — or `failed` with retry.

- **Migration:** `receipt_line` + `receipt_line_tag` + `account.card_last4` /
  `account.cash_account` — V11. Detection fields join the account-edit screen (`accounts`
  module).
- **`ReceiptParser`** (ARCH-03) + `AnthropicReceiptParser` via the official Java SDK: sends the
  edited image + instructions + `aiVocabulary()` + the AI note as the uncached suffix (§8) —
  never ledger contents (reworded ARCH-08). Tight output JSON schema; downscaling already
  happened client-side (9c). API key from config/env. **Send the edited bytes verbatim — do not
  re-encode server-side with `ImageIO`, which drops the EXIF orientation tag** (the 9b thumbnail
  bug); a sideways image parses worse. If a server-side normalisation of an un-edited original is
  ever needed, reuse the `ExifOrientation` + `ImageRotation` helpers from 9b.
- **Background worker:** Analyse → `processing`, HTTP returns immediately; pane greys, htmx polls
  a status fragment (§3.1); worker calls Messages, stores the immutable raw response
  (`parse_raw` — text, format-agnostic: JSON today, possibly TOON), seeds
  `receipt_line`s (term → category via `resolveTerm`, unresolved → uncategorised;
  note-instructed tag/beneficiary echoes resolved case-insensitively via `categories`/`debts`,
  unresolved silently dropped — suggestions, never creations; a recognised **cash-withdrawal
  line** — German supermarket cashback — seeds as a transfer line targeting the marked cash
  account), fills the
  denormalised header columns, detects the account (cash marker / last-4), flips `processed` —
  or `failed` (Retry → `pre_processed`, Discard). **Soft-delete tolerance (9b's delete ladder):**
  a receipt deleted mid-flight stays deletable without waiting; the worker, finding the row
  soft-deleted on completion, quietly abandons the result.
- **Tests:** parser behind a fake in unit tier (prompt assembly, seeding, resolution fallbacks,
  failure paths); integration round-trips for `receipt_line`; MockMvc for analyse/poll/retry. No
  live-API test in the suites.

**Done when:** a real receipt analysed end-to-end (manually, against the live API) seeds correct
draft lines; all suites green without network.

## 9f — Post-process: the full split toolkit

**Goal:** the §6.3 review surface — image left, editable item table right, full transaction
detail.

- **Header fields:** date, payee (existing picker incl. create-new), account (detected value
  pre-filled, always changeable), currency.
- **Item table:** description · amount · category (picker, AI suggestion as ghost) · tags (chips)
  · beneficiary `→ Person` · note; add/remove lines; edits persist to `receipt_line`(+`_tag`). A
  line's target may also be a **real account** (transfer leg — cashback → Cash); the split panel
  being reused already supports split transfers (7d.3).
- **`remaining 0,00 ✓`** readout (Σ items vs parsed total) as the parse sanity check.
- **⇄ Redistribute tax:** spreads the Tax line pro-rata over the other items, removes it, total
  preserved.
- **Tests:** unit for the redistribute arithmetic (pure); MockMvc acceptance for the table
  editing, the readout, and persistence; reuse of the register's picker fragments asserted.

**Done when:** a processed receipt can be brought to complete, balanced transaction detail
without leaving the pane.

## 9g — Confirm, link, reopen

**Goal:** the draft becomes a real transaction; the loop closes — including re-entry.

- **Confirm (§6.4, duplicates deferred):** materialise `receipt_line`s into a `transaction` +
  postings via the `operations` commit path — items as expense, **transfer** (real-account target,
  e.g. cashback), or beneficiary legs (per-currency leaf resolved at post time, beneficiary lines
  to the person's debt leaf — a debt increase), the paying account as the
  −total funding leg; `receipt_line_tag` → `posting_tag`; set `transaction_id`, → `committed`.
- **Jump both ways (§7):** receipt → its transaction in the register (selected, docked); the
  register's paperclip → this pane.
- **Reopen / re-enter:** reopen = `committed` → `processed`, transaction untouched; re-enter =
  soft-delete the old transaction (postings with it), materialise anew, repoint the link. No
  drift check.
- **Committed-delete dialog (9b's delete ladder, last rung):** deleting a `committed` receipt
  offers a 5-way choice — void the transaction ± delete files, or keep the transaction unlinked
  ± delete files, or cancel; the receipt row is soft-deleted (or just unlinked) per choice.
- **Tests:** unit for the materialisation shape (sum-to-zero by construction, leaf routing,
  funding leg); MockMvc acceptance capture→…→commit as the money-critical flow (replacing the
  retired Playwright smoke); integration for the link queries; reopen/re-enter acceptance incl.
  the soft-deleted predecessor.

**Done when:** confirm books a balanced transaction visible in the register with the paperclip;
reopen→re-enter voids and re-books; the old version remains inspectable soft-deleted.

## 9h — Batch (Batches API)

**Goal:** the backlog rhythm (§3.2) — pre-process many, select, **Process** once, −50 %.

- **Register multi-select → Process:** all `pre_processed` in the selection into **one** Batches
  API request; invalid-state members skipped with a count (§5.2); `batch_id` stored on each
  member; all flip `processing`.
- **Batch poller:** background polling of the batch; on completion distribute per-receipt results
  through the *same* seeding path as 9e — each member independently `processed` or `failed`.
  The UI is identical to single mode (§3.1).
- **Tests:** unit for submit/distribute against a faked batches client (mixed results, partial
  failures, skip counts); MockMvc for multi-select Process and the status badges.

**Done when:** a pile of pre-processed receipts round-trips through one batch and lands
individually reviewable, failures isolated per receipt.

---

## Open items intentionally left to their slice

- Prompt wording, output JSON schema, model id (floor ≥ Sonnet 4.6, NFR-09), Haiku→Sonnet
  escalation — 9e; escalation may be dropped if the floor model is cheap enough in practice.
- Polling cadence / SSE swap (T-RX-1) — 9e, only if polling grates.
- Keyboard map of the workflow pane — piecewise per slice, in the `keyboard.js` leaf, per the
  stage-7 rule.
