# A person cannot fund a whole split — the split panel drops the funding person

Status: needs-triage
Severity: high
Area: Transaction register — split panel (§3.10) × per-person debts (§3.5, data-model §7)

The split panel's **Account** field is a plain `<select>` of *real* accounts only
(`split-panel.html`), so it has never gained the person-capable datalist the simple dock got in
stage 8b.1 (register §3.3). When a transaction is funded **by a person** — you open the dock,
type `by Max` / `for Max` in Account, then press **Split** — the funding person is silently
dropped and the split misbooks.

This is the legitimate, common shared-expense shape the whole `debts` feature exists for:

> Max buys my groceries in a Swiss shop and hands me cash from the till, all on his tab.
> One receipt: `Food - Meat 25 CHF`, `Food - Milk 9,50 CHF`, `To Cash 10 CHF`, funded **by Max**.
> Wanted: `Max −44,50` (credit — he funded it), `Meat +25`, `Milk +9,50`, `Cash +10`, sum 0,
> single-currency CHF.

## What actually happened (before the guard below)

1. Press Split on a `by Max` (CHF) entry. The panel's `<select>` has no `by Max` option, so the
   browser submits its **first** option (`Cash (EUR)`) as the funding account.
2. That makes the split look **cross-currency** (Cash EUR funding + CHF spending lines). No base
   total was entered (the field wasn't shown on the person-funded dock and the seed didn't carry
   one), so every leg's `base_amount` was computed as **0,00**.
3. The engine's cross-currency check only verified `sum(base_amount) = 0` — and `0+0+0+0 = 0`
   **passed** — so a corrupt 4-leg transaction was persisted: three CHF debit legs with zero base,
   plus a **phantom `Cash (EUR)` credit leg with both amounts zero**, and **no `Max` leg at all**.
   `sum(amount)` was 44,50 (not zero); it only slipped through on the all-zero base sum.
4. The htmx commit then 500'd into an empty swap — "nothing appeared in the register, refresh
   didn't help" — because the balance exception wasn't caught by the commit handler.

A related symptom: on a fresh person-funded entry, pressing Split flips the Account to a real
account and the currency to a stale value, and the `<select>` won't let you type the person back
(it's a select, not a datalist).

## Already fixed (commit on stage/8b, the same session this issue was filed)

These close the **data-integrity** and **silent-failure** halves; they do **not** make the
workflow work:

- **Engine guard** (`LedgerService.validatedCrossCurrency`): a cross-currency transaction whose
  legs all carry `base_amount = 0` is rejected — `max(abs(base_amount))` must be non-zero. The
  base sum being zero is necessary but not sufficient.
- **Error surfacing**: the dock and split commit handlers now catch the engine's
  `UnbalancedTransactionException` and render it inline instead of 500-ing.
- **Clean block** (`RegisterSplitController.open`): pressing Split on a person-funded dock entry
  now refuses with a message rather than silently substituting a real account.

## Still to do — the actual feature (this ticket)

Give the split panel a person-capable funding leg, mirroring dock stage 8b.1:

- Convert the panel's Account `<select>` to the person-capable datalist (accepts `by`/`for`,
  inline-create, confirmed revival), resolved through the shared `/register/account/resolve`.
- Thread `fundingPersonName`/`fundingPersonDirection`/`fundingPersonRevive` through `SplitForm`,
  `SplitFormBinder`, `SplitEntry`, `DockSplitService.commit` (provision the funding person's leaf
  at commit, as the dock does), and `SplitEditService` (reload a person funding leg as the sigil).
- Currency: with no real account, the split is **single-currency** in the person's leaf currency
  (register §3.5 — "with no real account … it sets every leg, the transaction is single-currency,
  and it defaults to the involved person's leaf currency"), so the cross-currency header fields
  stay hidden. Decide the seed/currency handling so the EUR→stale-currency flip cannot recur.
- Tests: same tiers as 8b.2 — commit orchestration (unit) + resolve/commit/reload round-trip
  (integration), including the exact worked example above.

Relates to [[stage-8b-progress]] (8b.2) and the 8b.1 Account-field conversion. Owner decision
2026-07-20: block for now, file this for the full fix.
