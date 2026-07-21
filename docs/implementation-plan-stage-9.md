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

## 9b — Walking skeleton: capture → register → delete

**Goal:** a photo taken on the phone lands as a `new` receipt visible in the PC receipt register,
and can be deleted or discarded — the full storage and lifecycle plumbing, no image editing yet.

- **Migration:** `receipt` table (V9).
- **`ReceiptStorage`** (ARCH-07): store the immutable original under the configured Pi data
  directory; path scheme decided here (per-profile partition); serve originals/thumbnails via a
  controller endpoint. The abstraction hides the filesystem so tests can use a temp dir.
- **Capture:** mobile camera-only surface (camera → shoot → raw upload → `new`; multi-shot; no
  figures — §4) and a plain PC upload; `source` recorded.
- **Receipt register (§5):** thin-row list — thumbnail · captured · state · (parsed columns blank)
  — state as primary filter (default: work queue = everything except `committed` & `discarded`),
  captured-ascending order, last-90-days default range. Selection + context menu with **Delete**
  and **Discard** only (Process/Re-analyse arrive at 9e/9h).
- **Transitions live here:** capture→`new`; `discarded` (any non-committed); soft-delete
  (uncommitted only from mobile, per §4).
- **Tests:** integration round-trips for the repository; MockMvc acceptance for upload, register
  rendering, filter, delete/discard; storage unit tests against a temp dir.

**Done when:** phone-captured receipts appear in the register, filter by state, and can be
discarded/deleted; originals sit immutably on disk.

## 9c — Pre-process: crop leaf + AI note

**Goal:** a `new` receipt can be cleaned and annotated, becoming `pre_processed`.

- **Cropper.js leaf + pixel pass** (tech-stack §5, the second sanctioned JS leaf): crop · rotate ·
  tilt · grayscale · brightness · contrast, live canvas preview, all client-side; the baked
  **edited image** uploads to `edited_path`. Original never mutated; re-edit restarts from it.
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
  happened client-side (9c). API key from config/env.
- **Background worker:** Analyse → `processing`, HTTP returns immediately; pane greys, htmx polls
  a status fragment (§3.1); worker calls Messages, stores the immutable raw response
  (`parse_raw` — text, format-agnostic: JSON today, possibly TOON), seeds
  `receipt_line`s (term → category via `resolveTerm`, unresolved → uncategorised;
  note-instructed tag/beneficiary echoes resolved case-insensitively via `categories`/`debts`,
  unresolved silently dropped — suggestions, never creations; a recognised **cash-withdrawal
  line** — German supermarket cashback — seeds as a transfer line targeting the marked cash
  account), fills the
  denormalised header columns, detects the account (cash marker / last-4), flips `processed` —
  or `failed` (Retry → `pre_processed`, Discard).
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

- Exact Pi path scheme + thumbnail strategy — 9b.
- Prompt wording, output JSON schema, model id (floor ≥ Sonnet 4.6, NFR-09), Haiku→Sonnet
  escalation — 9e; escalation may be dropped if the floor model is cheap enough in practice.
- Polling cadence / SSE swap (T-RX-1) — 9e, only if polling grates.
- Keyboard map of the workflow pane — piecewise per slice, in the `keyboard.js` leaf, per the
  stage-7 rule.
