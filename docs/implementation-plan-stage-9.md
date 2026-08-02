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

## 9c — Pre-process: the processing screen, crop leaf + AI note ✅ **complete** (owner-confirmed 2026-08-01)

**Goal:** a `new` receipt can be cleaned and annotated, becoming `pre_processed`.

**Decisions grilled & settled 2026-07-31** (receipt doc updated to v0.4, data-model to v0.8):

- **The processing screen replaces the workflow pane.** Own URL, same tab, carrying the
  register's filter + order (leaves room for a future side-by-side embed); **one view per state**,
  every transition an explicit named button, never automatic — **the ▲▼ stage axis is retired**
  (§2.2). Common chrome: ↑/↓ prev/next receipt over the filtered list, Back/Esc to the register,
  Delete. After a delete: next receipt, else previous, else the list.
- **The `discarded` state is retired** (§2.1). V10 drops it from the state check; the register
  loses the 9b **Discard** context-menu entry and its filter option (work queue = everything
  except `committed`); "Discard" now means **stage-undo**. "Seen, not booked" = Delete + keep
  files. Soft-delete visibility / undelete stays backlog-on-explicit-need.
- **Delete ladder revised (supersedes 9b's `new` rung on PC):** PC delete always asks the 3-way
  keep/delete-files dialog, `new` included (register + processing screen; Delete key too); the
  mobile grid keeps its instant × on `new` tiles (the frictionless-reshoot flow).
- **Cropper.js v1.6.x**, vendored as UMD + CSS next to `htmx.min.js` (the second sanctioned JS
  leaf): crop · rotate · tilt · grayscale · brightness · contrast, live canvas preview, all
  client-side.
- **The `new` view:** original image + **Prepare for analysis** → edit mode (nav/Delete hidden;
  Save / Cancel, Esc = guarded Cancel). **Save always bakes** — even with zero adjustments —
  because the edited copy is where EXIF-upright is made physical (the 9b guard: feed Cropper the
  oriented pixels; the original keeps its raw-pixels + orientation-tag form, ARCH-07). Bake =
  **JPEG q≈0.9, long edge capped at 1568 px** (never upscaled; the API downscales beyond that
  anyway), with a visible note when downscaling occurred. One POST saves edited image + **AI
  note** (§8) + **edit recipe** (crop/rotation/tilt/filter JSON → new `edit_recipe` column);
  thumbnail regenerates from the edited image; → `pre_processed`.
- **The `pre_processed` view:** read-only edited image (the exact bytes the AI will get;
  full-size view) + note; **Edit** (recipe replayed onto the original, Save overwrites in place);
  **Discard edits** (→ `new`, edited file + recipe removed, thumb from original — **the AI note
  survives**); a **disabled Analyse placeholder** (9e). Browsing ↑/↓ never boots the editor.
- **Migration V10:** add `receipt.edit_recipe`, drop `discarded` from the state check
  (9d's migration shifts to **V11**, 9e's to **V12**).
- **Keyboard** (in the `keyboard.js` leaf, per the stage-7 rule): ↑/↓ navigate, Esc
  back / guarded cancel, Delete key opens the dialog.
- **Tests:** MockMvc for the per-state views, the save round-trip (multipart edited image + note
  + recipe), discard-edits, the revised delete ladder and menu/filter changes; storage unit tests
  for edited-file overwrite + thumbnail regeneration; the JS leaf itself stays untested per the
  standing rule (no browser tier).

**Done when:** a receipt can be cropped/cleaned/annotated on the PC and lands `pre_processed`,
re-editable with its recipe replayed; `discarded` is gone from schema and UI.

## 9d — The AI Vocabulary (`categories` module) ✅ **complete** (owner-confirmed 2026-08-01)

**Goal:** the curated projection exists and is editable — testable end-to-end without any AI call.

**Decisions grilled & settled 2026-08-01** (data-model updated to v0.9, §13.3):

- **Both types project** (receipts carry income lines: deposit returns, discounts, payback), with
  opposite defaults — **expense visible, income hidden**.
- **Visibility is a per-node tri-state:** `true` / `false` / `null` = inherit the nearest set
  ancestor, else the type default. No propagation writes — a group toggle touches no child rows;
  an explicit override survives later group edits; "back to inherit" is an explicit choice. A
  group's flag is an inheritance lever, not a mask (an overridden-visible leaf under a hidden
  group still projects, full path included); leafless groups are pruned.
- **Migration V11:** `category_ai_config` per data-model §13.3 — `visible` **nullable** (the
  tri-state), `alias`, `ai_note`, unique per `account_id`; absence of a row = inherit everything.
- **Public API:** `aiVocabulary()` — the AI-facing tree (effective names at every level — a group
  alias renames its children's paths; hidden pruned, notes attached); `resolveTerm(text)` —
  **leaves-only**, case-insensitive, effective path first then unique bare effective leaf name;
  group / hidden / real-name-of-aliased / ambiguous / unknown → empty (→ uncategorised). The 9e
  prompt instructs: no fitting leaf → no category, never a near-miss. **Tag resolution** for AI
  echoes: a new non-creating lookup beside `resolveChip` — full `Parent:Child` path only,
  case-insensitive, any missing segment → empty.
- **Consistency:** rename automatic (attaches by `account_id`); subdivision leaves the row on the
  now-group parent, children inherit; deletion removes the subtree's config rows
  (`DeletionService`), the reassignment target untouched. (No category merge exists yet.)
- **Editor:** "AI parsing" section on category-edit — three radios where **Inherit spells out the
  effective result and its source** ("Inherit — currently: visible (via parent 'Food')"), alias
  field, AI-note textarea; own POST, upsert, all-default deletes the row, redirect back to the
  categories list (owner call — the saved deviation is visible there as a list annotation).
- **List display (deviations only):** annotations render as quiet inline labels (not chips) — a
  visibility deviation from the type default shows "AI: hidden/visible" (inherited ones append
  "(via ⟨ancestor⟩)"); and, **only while the category is visible to the AI**, an alias shows
  "AI alias: ⟨text⟩" and a note shows "AI note: ⟨text⟩" in full (notes are short). A hidden
  category's alias/note never reach the parser, so they are not surfaced. Default rows stay clean,
  so the eye lands on the curated spots.
- **Tests:** `sqlLogicTest` for the projection/resolution SQL (inheritance chains, type defaults,
  aliased paths, hidden pruning, ambiguity, case handling — the same effective-visibility logic
  feeds the list annotations); integration round-trips for the config repository; MockMvc for the
  editor section and list annotations.

**Done when:** the vocabulary renders and resolves per the crafted scenarios and is editable from
category-edit.

## 9e — Analyse (single): worker, parser, seeding ✅ **complete** (owner-confirmed 2026-08-02)

**Goal:** one `pre_processed` receipt goes through the Messages API and comes back `processed`
with seeded draft lines — or `failed` with retry. Decisions below grilled & settled 2026-08-01.

- **Migration — V12:** `receipt_line` + `receipt_line_tag` + `account.card_last4` /
  `account.cash_account`, **plus** the receipt header/telemetry extension (`receipt_time`,
  `merchant_city`, `merchant_country`, `receipt_number`, `parse_error`, the four token counts,
  `parse_cost`) and the settings AI section (`ai_model`, `ai_api_key`, four per-MTok price
  rates) — data-model §13.1/§3.8. Detection fields join the account-edit screen (`accounts`
  module); the AI settings join the Settings screen (`ledger` module).
- **Output format is TOON**, not JSON (owner call: Sonnet 5 emits it natively; 30–60 % fewer
  output tokens). Decoded with `dev.toonformat:jtoon` (Maven Central, MIT, Java 17);
  `parse_raw` stores the raw TOON verbatim. Shape: `merchant{name,city,country}`,
  `transaction{date,time,account,totalAmount,currency,receiptNumber}`,
  `items[]{name,quantity,unitPrice,totalPrice,category,tags,beneficiary,transfer}` —
  `category` echoes a full effective path from the vocabulary (or stays empty: "no fitting
  leaf → no category"); `tags`/`beneficiary` are note-instructed echoes only; `transfer`
  carries the word `cash` or a card's printed last-4 (supermarket cashback *and* ATM
  cash-in/out slips), resolved through the same §13.4 detection as the paying account —
  unresolved values seed a targetless transfer line for post-process.
- **`ReceiptParser`** (ARCH-03) + `AnthropicReceiptParser` via the official Java SDK. Prompt =
  `[system: instructions + vocabulary + TOON skeleton with one worked text example]`
  `[user: image + ai_note]` — never ledger contents (reworded ARCH-08). The vocabulary renders
  flat: `Income:` / `Expense:` headers, one effective leaf path per line (`Food - Sweets`),
  notes appended after an em-dash, a group's note on a group line above its leaves. No image
  few-shots. **No `cache_control` in 9e** — sporadic single parses never hit the 5-minute TTL
  and a cache write costs +25 %; the breakpoint lands with 9h, where a batch genuinely shares
  the prefix. The prefix/suffix *ordering* discipline (§8) is kept now so it can. Model id from
  `settings.ai_model` (default `claude-sonnet-5`); the Haiku→Sonnet escalation ladder is
  **dropped**. API key: `settings.ai_api_key` first, `ANTHROPIC_API_KEY` env as fallback —
  write-only masked field on the Settings screen (NFR-04 amended; data-model §3.8).
  Downscaling already happened client-side (9c). **Send the edited bytes verbatim — do not
  re-encode server-side with `ImageIO`, which drops the EXIF orientation tag** (the 9b thumbnail
  bug); a sideways image parses worse. If a server-side normalisation of an un-edited original is
  ever needed, reuse the `ExifOrientation` + `ImageRotation` helpers from 9b.
- **Background worker:** Analyse → `processing`, HTTP returns immediately; pane greys, htmx polls
  a status fragment (§3.1, 2 s cadence); a dedicated single-thread executor calls Messages,
  stores `parse_raw`, records the usage token counts and the **frozen** `parse_cost` (settings
  rates × counts, USD, never recomputed on a rate edit), seeds `receipt_line`s and the
  denormalised header, detects the account (cash marker / last-4), flips `processed` — or
  `failed` with the reason in `parse_error` (Retry → `pre_processed`, or Delete). **Seeding is
  lenient:** anything that decodes is seeded — missing header fields stay null, `resolveTerm`
  misses seed uncategorised, unresolved tag/beneficiary echoes are silently dropped
  (suggestions, never creations), even zero items still flips `processed`; post-process (9f) is
  the fixing surface. Only an undecodable response or a transport error fails; whatever body
  came back is still kept in `parse_raw`. `description` = item name (quantity folded in as
  `2× …` when > 1); `amount` = `totalPrice`; quantity/unitPrice otherwise live only in
  `parse_raw`. **Startup sweep:** on boot, single-mode `processing` rows (`batch_id` null, not
  soft-deleted) flip to `failed` — the worker thread died with the JVM; batch rows are exempt
  (9h's poller resumes them). **Soft-delete tolerance (9b's delete ladder):**
  a receipt deleted mid-flight stays deletable without waiting; the worker, finding the row
  soft-deleted on completion, quietly abandons the result.
- **Tests:** parser behind a fake in unit tier (prompt assembly, TOON decode + lenient seeding,
  resolution fallbacks, cost arithmetic, failure paths, startup sweep); integration round-trips
  for `receipt_line` and the new columns; MockMvc for analyse/poll/retry. No live-API test in
  the suites.

**Done when:** a real receipt analysed end-to-end (manually, against the live API) seeds correct
draft lines with token counts and cost recorded; all suites green without network.

## 9f — Post-process: the full split toolkit ✅ **complete** (owner-confirmed 2026-08-02)

**Goal:** the §6.3 review surface — image left, editable item table right, full transaction
detail. Decisions below grilled & settled 2026-08-02 (data-model to v0.11).

- **Review changes nothing but the draft.** Post-process is editing the AI analysis result;
  Save persists the draft and the receipt **stays `processed`** — `committed` (9g's Confirm) *is*
  the reviewed state. Saved-but-unconfirmed = interrupted work, deliberately unmarked (owner
  call: no `reviewed_at`, no extra state).
- **Panel reuse = shared line-editor core.** The per-line row editor (category/account picker,
  amount, tags, note, `→ Person`) is extracted from `fragments/split-panel.html` into a
  sub-fragment both surfaces render; the register keeps its wrapper (funding header, view-state,
  dock POSTs), receipts get their own wrapper on the `processed` view posting to
  `/receipts/{id}/…`, assembled via `operations`' public API (edge exists). The
  `keyboard.js` `data-split-*` markup contract is shared, so the live remaining readout comes
  free. No whole-fragment mode switch, no copy.
- **One explicit Save.** Header + all lines are one form; Save delete-and-reinserts the draft
  lines (`receipt_line` + `_tag`; header chips expand per-line, T-RX-3) and updates the header.
  ↑/↓/Esc/Back get the 9c-style dirty guard; no autosave, navigation never writes.
- **Migration V14** (V13 was taken by the 9e follow-up `settings.ai_system_prompt`):
  `receipt.payee_id` (header payee, created-on-Save via the picker's
  create-new; `merchant_text` stays the parse fact) and `receipt_line.ai_target_text` (the AI's
  raw target term — unresolved category echo, or the transfer signal stored as
  `transfer: cash` / `transfer: card •1234`). The 9e seeder now populates it.
- **Header:** date · payee (picker text **prefilled from the parsed merchant as `name - city -
  country`** so every recognised part carries over, not just the name — case-insensitive exact
  match pre-selects, else create-new is one Enter away; nothing persists until Save) ·
  account (detected pre-filled, always changeable) · currency · **editable total**
  (mis-read totals are fixable; persists to `total_amount`). `remaining = total − Σ lines`,
  live; null total → neutral "no total" hint instead of ✓/⚠. **Currency ≠ account currency
  warns at Save, never blocks** (lenient-draft philosophy); 9g's Confirm hard-blocks.
  Cross-currency receipt commits → backlog.
- **Ghost = hint on unresolved lines.** Resolved AI categories display as plain picker values
  (override is one keystroke); unresolved lines show `ai_target_text` as a grey non-committing
  hint ("AI said: …"), which also marks a targetless transfer line for what it is; resolved
  lines carry the term as a provenance tooltip. The 9e parse-telemetry line stays at the bottom.
- **⇄ Redistribute is a per-line action** — "spread this line over the others, remove it"; no
  Tax-detection heuristics (two VAT lines = two clicks; works for Pfand/rounding lines too).
  Spread base: **all lines except real-account transfer legs** (beneficiary lines absorb — their
  items bore tax too; negative lines participate with negative shares); cent-level
  largest-remainder so the total is preserved exactly; refused when the absorbing lines sum to
  zero. Server round-trip re-rendering unsaved form state — persisted only at Save.
- **Tests:** unit for the redistribute arithmetic (pure) and the seeder's `ai_target_text`
  cases; integration round-trips for the V13 columns; MockMvc acceptance for the surface, the
  Save round-trip, the readout, the mismatch warning, ghost/hint rendering, and the shared
  line-editor fragment reuse. The JS leaf stays untested per the standing rule.

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

- SSE swap for the status poll (T-RX-1) — later, only if the 2 s polling grates. (Prompt
  wording, TOON schema, model id, and the dropped escalation ladder were settled in the
  2026-08-01 grilling — see §9e.)
- Keyboard map of the workflow pane — piecewise per slice, in the `keyboard.js` leaf, per the
  stage-7 rule.
