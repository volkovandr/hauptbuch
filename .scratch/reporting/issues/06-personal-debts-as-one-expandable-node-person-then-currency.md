# Personal debts: one "Personal debt" row that expands to people, then to currencies

Status: needs-info
Category: enhancement
Severity: medium
Area: Reporting (`analytics` module: `ReportQueryRepository`, `ReportDataFetcher`, `ReportGridBuilder`, `AutoExpansion`)

## Today

Per-person debt leaves (`data-model.md` §7) have no parent account. Each (person, currency) leaf is
its own root. So that the Account/Category dimension doesn't list every person's leaf under its
cosmetic name (`personal.<CUR>`), `ReportQueryRepository` (`ACCOUNT_DIMENSION_KEY`/`_LABEL`,
`topLevelAccounts`) merges them into **one top-level row per currency**: "Personal debts (EUR)",
"Personal debts (CHF)", and so on. Those rows use a synthetic key `personal:<CUR>` and **can't be
expanded**. Java recognises them by string prefix (`AutoExpansion.isPersonLeafBucket`). Which person
owes what can only be seen through the separate **Person** dimension.

## Wanted (owner, 2026-09-23)

A single synthetic tree on the Account/Category dimension:

```
Personal debt                  ← one top-level row, whatever the number of currencies
├─ Max                         ← one row per person (name resolved via account_owner → person)
│  ├─ EUR                      ← the person's actual leaf account
│  └─ CHF
└─ Doe
   └─ EUR
```

- Top level: exactly **one** "Personal debt" row, never one row per currency.
- Expanding it lists **people**. Expanding a person lists that person's **currency leaves**. The
  leaves are the real `person_leaf` accounts, so cells and filters resolve to real account ids.
- Behaves like every other tree node (`reporting.md` §9): expand triangle only where children exist,
  remembered expansion on a saved Report, `auto`/collapsed defaults, subtotal vs group-header parents,
  empty-row suppression.

## Design notes for the implementer

- **Synthetic levels.** Neither "Personal debt" nor a person is an account row, so the two upper
  levels need their own children queries: people via `account_owner` → `person`, and a person's
  leaves via `account_owner`. They also need their own subtree predicates for cells and totals: "all
  `person_leaf` accounts" for the top, and "leaves owned by person X" for a person. Follow how
  `childAccountCandidates`/`accountHierarchyPredicate` work, as siblings of them. Don't add a
  different mechanism.
- **Typed node kind, not string prefixes.** Frontier keys today recover a real account id from their
  last `|` segment (`ReportDataFetcher.realId`). A person level breaks that assumption: its id is a
  `person_id`. Give `TopLevelNode` an explicit kind/discriminator column returned by the SQL (as
  `has_children` already is), and remove `isPersonLeafBucket`'s `startsWith("personal:")`. This takes
  in the "sturdier discriminator" cleanup from the stage e2 code review.
- **Persisted keys.** Saved Reports may already have `personal:<CUR>` keys in `expanded_node_keys`.
  Those rows were never expandable, so no real expansion state exists for them. Old keys just need
  to be ignored gracefully, as stale keys already are.
- **Soft-deleted people** keep their history (§7). They list under "Personal debt" whenever they
  have activity in range, and are hidden by empty-row suppression otherwise, like any other row.
- **Scope.** Debt leaves are `asset`, so the tree only appears when the Scope includes assets
  (balance sheet, net worth). This is unchanged from today.
- **Docs.** Record the synthetic tree in `reporting.md`: §3/§4 dimension table, a line in §9.1. The
  code comment on `ACCOUNT_DIMENSION_KEY` refers to "Q-REP-1" for deferring per-person expansion, but
  Q-REP-1 is actually about net worth. Fix that reference when the code changes.
- **Tests.** `sqlLogicTest`: the three levels' children queries, and a person's subtotal equal to the
  sum of their leaves (cross-currency case included). Unit: frontier walk through the synthetic
  levels and the discriminator. Integration: rendered tree with expand toggles on a saved Report.

## Open question (why `needs-info`)

`data-model.md` §7 says a person's currencies are **never netted** ("show currencies side by side,
never net across them"; a base-currency total is only a "supplementary gloss"). A **Max** subtotal
row, and the **Personal debt** row above it, would add EUR and CHF debts together in base currency.
Reports already do this for any multi-currency parent (e.g. `Cash` over `Cash-EUR`/`Cash-USD`), so
this reads as the accepted gloss. The owner should confirm one of:

1. Subtotals are fine: the base-currency sum at person/top level is the §7 "supplementary gloss".
2. The person and top levels always render as **group headers** (blank own cells) regardless of the
   Report's subtotal setting, so no cross-currency net is ever shown.

Once answered, set `Status: ready-for-agent`.

## Comments

Filed 2026-09-23 from an owner discussion while triaging the stage e leftovers. It replaces the
"typed discriminator for `personal:`" cleanup (stage e2 `/code-review` finding 4), which is folded in
above.
