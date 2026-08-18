# Person-transfer detection missed on the first receipt after adding the AI note, despite the person already existing

Status: needs-info
Category: bug
Severity: medium
Area: Receipts — AI analyse seeding × person/transfer resolution (`ReceiptSeeder`,
`PersonService.matchExact`, category AI-vocabulary notes)

Found during first real-conditions use after the Pi deployment (2026-08-18). Sequence reported:

1. Person "Bobby" created in advance (before any of the below).
2. An AI note was added to category `Food > Sweets`: "M&Ms and Haribo should be categorized as
   transfer for Bobby."
3. First receipt containing a matching item: the app did **not** create the transfer-to-Bobby
   posting, but it *did* correctly show a hint "AI thinks this is transfer for Bobby."
4. The owner then manually typed "for Bobby" into the line's Category field (see also issue 13),
   saved, and committed — this worked.
5. A **subsequent** receipt with similar items worked correctly from analysis onward, no manual
   fix-up needed.

## What exists today

- `ReceiptPromptBuilder` (`:68-76`) tells the model to fill a `beneficiary` field only when a
  category's AI note instructs it to; the note itself (`:140-147`) is passed to the model as plain
  appended text — there's no code-side parsing of the note, the model reads it directly.
- `ReceiptSeeder.lineOf`/`beneficiary` (`:111-121`, `:174-183`) resolves whatever name the model
  echoed back via `PersonService.matchExact(name)` — a live, uncached DB lookup
  (`findAllByNameExact`) each time, so there's no obvious request-level or app-level caching bug.
- Per `ReceiptSeeder`'s own class-level Javadoc, an unresolved beneficiary echo is **silently
  dropped as a suggestion, never auto-created** — `matchExact` only recognizes an already-`Live`
  Person row. This matches the hint-but-no-posting behavior seen in step 3 above.
- The open question is *why* `matchExact("Bobby")` failed to find Bobby on the very first receipt
  when the person was reportedly created beforehand — a plain exact-match query against an existing
  Live row should have succeeded. Nothing in the reviewed code explains a first-time-only miss
  (no cache, no batching-related snapshot found in `PersonService`).

## Questions for the owner

1. Was that first receipt analyzed via the **interactive** single-analyze path or the **batch**
   (9h, Anthropic Batches API) path? The two have separate call sites
   (`AnthropicReceiptParser` vs. `AnthropicReceiptBatchClient`), and it would help to know which
   one exhibited the miss.
2. Roughly how much time passed between creating "Bobby" and analyzing that first receipt — enough
   to be confident the person creation had definitely been saved/committed first?
3. Do you recall (or can you check) the exact capitalization/spelling used both when creating the
   person and in the AI's echoed hint — "Bobby" both times, or could there have been a case/spacing
   mismatch?

If reproducible, this needs a repro (fresh person + note + first receipt) before it can move past
`needs-info` — a one-off timing fluke vs. a real first-use bug in resolution would need different
fixes.
