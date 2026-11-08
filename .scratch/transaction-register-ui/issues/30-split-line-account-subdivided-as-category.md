# A split line pointing at a real account is subdivided as if it were a category

Status: open
Category: bug
Severity: high (silently corrupts the ledger; the UI offers no way to undo it)
Area: Transaction register — split panel (`DockSplitService.resolveLine`, `CurrencyLeafService`, line editor hidden inputs)

## Symptom
Recording a cross-currency cash withdrawal as a split (funding: a EUR bank account; header
category: `Cash-SEK`, an `asset` account in SEK with postings) created two new `asset` accounts
under `Cash-SEK` and left the withdrawal out of `Cash-SEK` itself:

```
Account opened: id=329, name=SEK
Category currency leaf provisioned: id=329, category=Cash-SEK, currency=SEK
Account opened: id=330, name=NOK
Category currency leaf provisioned: id=330, category=Cash-SEK, currency=NOK
```

`Cash-SEK` became a parent, its existing postings moved to the new `SEK` child, and the new posting
landed on the `NOK` child. `Cash-SEK` showed no increase. The owner repaired it by editing the
database; the UI has no operation to undo it.

## Root cause
Two unguarded seams:

1. **No type check on the category branch.** `DockSplitService.resolveLine` sends every line that
   is neither a transfer nor a person line to `CurrencyLeafService.resolveCurrencyLeaf(categoryId,
   lineCurrency)`. That routine implements data-model §6.5 for *categories* (income/expense) and
   accepts any account id. Handed an `asset` id whose currency differs from the line's, it runs the
   category subdivision: the account becomes a parent, its postings move to an own-currency child
   (log pair 1), and a child for the incoming currency is created (log pair 2). Both children
   inherit the parent's `asset` type.
2. **Text and hidden ids can drift apart.** The split panel seeds line 1 from the dock (including
   the header's category id and `lineTransferDirection`). A line's visible text is turned into the
   hidden `lineCategoryId` / `lineTransferDirection` only by the `/categories/resolve` round-trip.
   An id (here `Cash-SEK`'s) can therefore reach commit without the direction that makes it a
   transfer, and with text that no longer names it. Exactly how the direction was lost in the
   owner's session is unverified (the likely route is typing a currency code into the line's
   category field, then saving before/without a successful resolve).

The category resolver itself only offers income/expense leaves, so there is no intended UI path
to this state; the server simply trusts the posted id.

## Accepted solution (owner, 2026-11-08: A and B)
**A — server guard (the fix).** The category branch refuses an account that is not an
income/expense category, with a message naming the account and pointing at the transfer syntax
("'Cash-SEK' is an account, not a category — use To → / From ← for a transfer"). Put the check in
the shared `CurrencyLeafService.resolveCurrencyLeaf` so every caller is covered
(`DockSplitService`, `DockCommitService`, `DeletionService`, `StagedTransactionResolver`), unless
a caller legitimately routes non-category ids (verify each before enforcing; do not weaken it).
Nothing is created or moved when it refuses.

**B — drift fix (root cause).** When a split line's category text is edited, its hidden
`lineCategoryId` and `lineTransferDirection` are cleared immediately, before any round-trip, so
a stale id cannot survive an unresolved edit and the commit fails with "Each split line needs a
category" instead. Applies to the dock's category field if it shares the wiring. Keep it in the
existing keyboard/htmx markup — no fourth JS leaf (CLAUDE.md §1.6).

## Tests (write first)
- Unit (`CurrencyLeafServiceTest`, services are unit-tested with mocked repositories): an `asset`
  account is refused, whether its currency differs (would subdivide) or matches (would silently
  post to an account as a "category"); no leaf is created and no posting is moved.
- Integration (MockMvc) for B's rendered markup, per the htmx-attribute rule; `browserTest` only
  if the clearing is JS behaviour.

## Open
- Why the line currency was NOK while the header was believed to read SEK — unverified; does not
  change the fix.
- The UI cannot undo a mis-created currency leaf (merge/delete of an unposted or mistaken leaf).
  Out of scope here; raise separately if wanted.
