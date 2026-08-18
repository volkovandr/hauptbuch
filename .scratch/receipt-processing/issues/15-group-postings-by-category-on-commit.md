# Receipt commit should create one posting per category (summed), not one per line item

Status: needs-triage
Category: enhancement
Severity: medium
Area: Receipts — commit posting generation (`ReceiptSplitEntries`, `DockSplitService`) × register
display (`RegisterRowRenderer`)

Sourced from `docs/potential-feature-ideas.md` ("Receipts page" section, added 2026-08-18) after
first real-conditions use: "When entering a transaction into the register from a receipt, the
postings should not be created per each item in the receipt, but per each category. group by
category, sum over amount. Otherwise it appears weirdly in the register, the categories column
shows something like 'Food, Food, Food +20' instead of just 'Food'."

## What exists today

Confirmed by design, not a bug: today one posting/leg is created per receipt line item, with no
aggregation by category —

- `ReceiptSplitEntries.of`/`lineOf` (`:35-57`, `:59-76`) build one `SplitLineDraft` per
  `WorkingLine`.
- `DockSplitService.sameCurrencyLegs` (`:172-189`, similarly `crossCurrencyLegs` at `:288-328`)
  loops every entry's lines and adds one counter-leg `PostingDraft` per line.
- `RegisterRowRenderer.cellFor` (`:177-188`, `MAX_CATEGORY_CHIPS` at `:38`) renders one chip per
  counterpart leg with a capped `· +n` overflow — this is the display symptom described above, and
  it's a direct consequence of the commit-time behavior, not an independent display bug.

Existing tests: `ReceiptSplitEntriesTest`, `DockSplitServiceTest`, `RegisterRowRendererTest`.

## Open questions before this can be scoped

1. **Scope of "same category."** Category leaves are per-currency by construction
   (data-model §6.5/CLAUDE.md §4), so within one commit all lines routed to `Food-EUR` really are
   the same leaf — grouping should be safe on that front. But: should grouping merge across lines
   that share a category leaf but carry **different tags**, or a **different per-line AI note**? If
   two `Food` lines have different tags, summing them into one posting means one of the two
   line-level tag sets has to win, get unioned, or the merge has to be restricted to lines with
   identical tags.
2. **Person-transfer legs.** Should a category-leg merge also apply to `for <person>`/`by <person>`
   transfer legs that repeat (e.g. two Bobby items on one receipt), or should grouping be scoped to
   plain category legs only, leaving transfer legs one-per-line as today?
3. **Is this purely a commit-time posting change, or does the *register display* also need its own
   independent chip-dedupe fix** (summing what's already-committed postings for display, without
   changing what commit creates)? A display-only fix is far smaller but doesn't reduce the actual
   posting count in the ledger; a commit-time change is the "real" fix the request describes but
   changes the ledger's audit trail (splitting a committed transaction back into original receipt
   line amounts becomes lossy — the per-item detail only survives on the `receipt`/line-item
   records, not in the postings).
4. Does this apply to the receipt-commit path only, or should the same grouping apply to a manually
   entered split transaction in the register dock (`DockSplitService` is shared by both callers per
   the code above)?

## Comments

Filed 2026-08-18 from first real-conditions testing after the Pi deployment; not yet triaged.
